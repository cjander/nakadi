package org.zalando.nakadi.service;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.echocat.jomon.runtime.concurrent.RetryForSpecifiedCountStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zalando.nakadi.config.NakadiSettings;
import org.zalando.nakadi.domain.CursorError;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.EventTypePartition;
import org.zalando.nakadi.domain.NakadiCursor;
import org.zalando.nakadi.domain.Subscription;
import org.zalando.nakadi.domain.Timeline;
import org.zalando.nakadi.domain.TopicPartition;
import org.zalando.nakadi.exceptions.IllegalScopeException;
import org.zalando.nakadi.exceptions.InternalNakadiException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.InvalidStreamIdException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.NoSuchSubscriptionException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.exceptions.Try;
import org.zalando.nakadi.exceptions.UnableProcessException;
import org.zalando.nakadi.exceptions.runtime.OperationTimeoutException;
import org.zalando.nakadi.exceptions.runtime.ZookeeperException;
import org.zalando.nakadi.repository.EventTypeRepository;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.repository.db.SubscriptionDbRepository;
import org.zalando.nakadi.repository.zookeeper.ZooKeeperHolder;
import org.zalando.nakadi.security.Client;
import org.zalando.nakadi.service.subscription.zk.CuratorZkSubscriptionClient;
import org.zalando.nakadi.service.subscription.zk.ZkSubscriptionClient;
import org.zalando.nakadi.service.timeline.TimelineService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.text.MessageFormat.format;
import static org.echocat.jomon.runtime.concurrent.Retryer.executeWithRetry;

@Component
public class CursorsService {

    private static final Logger LOG = LoggerFactory.getLogger(CursorsService.class);

    private static final String PATH_ZK_OFFSET = "/nakadi/subscriptions/{0}/topics/{1}/{2}/offset";
    private static final String PATH_ZK_PARTITION = "/nakadi/subscriptions/{0}/topics/{1}/{2}";
    private static final String PATH_ZK_PARTITIONS = "/nakadi/subscriptions/{0}/topics/{1}";
    private static final String PATH_ZK_SESSION = "/nakadi/subscriptions/{0}/sessions/{1}";

    private static final String ERROR_COMMUNICATING_WITH_ZOOKEEPER = "Error communicating with zookeeper";

    private static final int COMMIT_CONFLICT_RETRY_TIMES = 5;

    private final ZooKeeperHolder zkHolder;
    private final TimelineService timelineService;
    private final SubscriptionDbRepository subscriptionRepository;
    private final EventTypeRepository eventTypeRepository;
    private final NakadiSettings nakadiSettings;

    @Autowired
    public CursorsService(final ZooKeeperHolder zkHolder,
                          final TimelineService timelineService,
                          final SubscriptionDbRepository subscriptionRepository,
                          final EventTypeRepository eventTypeRepository,
                          final NakadiSettings nakadiSettings) {
        this.zkHolder = zkHolder;
        this.timelineService = timelineService;
        this.subscriptionRepository = subscriptionRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.nakadiSettings = nakadiSettings;
    }

    /**
     * It is guaranteed, that len(cursors) == len(result)
     **/
    public List<Boolean> commitCursors(final String streamId, final String subscriptionId,
                                       final List<NakadiCursor> cursors, final Client client)
            throws ServiceUnavailableException, InvalidCursorException, InvalidStreamIdException,
            NoSuchEventTypeException, InternalNakadiException, NoSuchSubscriptionException, UnableProcessException,
            IllegalScopeException {

        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        validateSubscriptionCursors(subscription, cursors);
        validateSubscriptionReadScopes(subscription, client);
        validateStreamId(cursors, streamId, subscriptionId);

        final Map<EventTypePartition, List<NakadiCursor>> cursorsByPartition = cursors.stream()
                .collect(Collectors.groupingBy(
                        cursor -> new EventTypePartition(cursor.getEventType(), cursor.getPartition())));

        final HashMap<EventTypePartition, Iterator<Boolean>> partitionCommits = new HashMap<>();
        for (final EventTypePartition etPartition : cursorsByPartition.keySet()) {

            final Iterator<Boolean> commitResultIterator =
                    commitPartitionCursors(subscriptionId, cursorsByPartition.get(etPartition)).iterator();
            partitionCommits.put(etPartition, commitResultIterator);

            LOG.debug("[COMMIT_CURSORS] committed {} cursor(s) for partition {}",
                    cursorsByPartition.get(etPartition).size(), etPartition);
        }

        return cursors.stream()
                .map(cursor -> partitionCommits.get(cursor.getEventTypePartition()).next())
                .collect(Collectors.toList());
    }

    private void validateStreamId(final List<NakadiCursor> cursors, final String streamId,
                                  final String subscriptionId)
            throws ServiceUnavailableException, InvalidCursorException, InvalidStreamIdException,
            NoSuchEventTypeException, InternalNakadiException {

        if (!isActiveSession(subscriptionId, streamId)) {
            throw new InvalidStreamIdException("Session with stream id " + streamId + " not found");
        }

        final HashMap<EventTypePartition, String> partitionSessions = new HashMap<>();
        for (final NakadiCursor cursor : cursors) {
            final EventTypePartition etPartition = cursor.getEventTypePartition();
            String partitionSession = partitionSessions.get(etPartition);
            if (partitionSession == null) {
                partitionSession = getPartitionSession(subscriptionId, cursor.getTopic(), cursor);
                partitionSessions.put(etPartition, partitionSession);
            }

            if (!streamId.equals(partitionSession)) {
                throw new InvalidStreamIdException("Cursor " + cursor + " cannot be committed with stream id "
                        + streamId);
            }
        }

        LOG.debug("[COMMIT_CURSORS] stream IDs validation finished");
    }

    // TODO: Maybe it is better to use SubscriptionCursorWithoutToken.
    private String getPartitionSession(final String subscriptionId, final String topic, final NakadiCursor cursor)
            throws ServiceUnavailableException, InvalidCursorException {
        try {
            final String partitionPath = format(PATH_ZK_PARTITION, subscriptionId, topic, cursor.getPartition());
            final byte[] partitionData = zkHolder.get().getData().forPath(partitionPath);
            final TopicPartition partitionKey = new TopicPartition(topic, cursor.getPartition());
            return CuratorZkSubscriptionClient.deserializeNode(partitionKey, partitionData).getSession();
        } catch (final KeeperException.NoNodeException e) {
            throw new InvalidCursorException(CursorError.PARTITION_NOT_FOUND, cursor);
        } catch (final Exception e) {
            LOG.error(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER);
        }
    }

    private boolean isActiveSession(final String subscriptionId, final String streamId)
            throws ServiceUnavailableException {
        try {
            final String sessionsPath = format(PATH_ZK_SESSION, subscriptionId, streamId);
            return zkHolder.get().checkExists().forPath(sessionsPath) != null;
        } catch (final Exception e) {
            LOG.error(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER);
        }
    }

    private List<Boolean> commitPartitionCursors(final String subscriptionId, final List<NakadiCursor> cursors)
            throws ServiceUnavailableException {
        final NakadiCursor first = cursors.get(0);
        final String offsetPath = format(
                PATH_ZK_OFFSET, subscriptionId, first.getTopic(), first.getPartition());
        // TODO: fix while switching to timelines
        final TopicRepository topicRepository = timelineService.getTopicRepository(first.getTimeline());
        try {
            @SuppressWarnings("unchecked")
            final List<Boolean> committed = executeWithRetry(() -> {
                        final Stat stat = new Stat();
                        final byte[] currentOffsetData = zkHolder.get()
                                .getData()
                                .storingStatIn(stat)
                                .forPath(offsetPath);
                        final String currentOffset = new String(currentOffsetData, Charsets.UTF_8);
                        NakadiCursor currentMaxCursor = first.withOffset(currentOffset);

                        final List<Boolean> commits = Lists.newArrayList();
                        for (final NakadiCursor cursor : cursors) {
                            if (topicRepository.compareOffsets(cursor, currentMaxCursor) > 0) {
                                currentMaxCursor = cursor;
                                commits.add(true);
                            } else {
                                commits.add(false);
                            }
                        }

                        if (!currentMaxCursor.getOffset().equals(currentOffset)) {
                            final String offsetToUse = currentMaxCursor.getOffset();
                            zkHolder.get()
                                    .setData()
                                    .withVersion(stat.getVersion())
                                    .forPath(offsetPath, offsetToUse.getBytes(Charsets.UTF_8));
                        }
                        return commits;
                    },
                    new RetryForSpecifiedCountStrategy<List<Boolean>>(COMMIT_CONFLICT_RETRY_TIMES)
                            .withExceptionsThatForceRetry(KeeperException.BadVersionException.class));

            return Optional.ofNullable(committed)
                    .orElse(Collections.nCopies(cursors.size(), false));
        } catch (final Exception e) {
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
        }
    }

    public List<NakadiCursor> getSubscriptionCursors(final String subscriptionId, final Client client)
            throws NakadiException {
        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        validateSubscriptionReadScopes(subscription, client);
        final ImmutableList.Builder<NakadiCursor> cursorsListBuilder = ImmutableList.builder();

        for (final String eventTypeName : subscription.getEventTypes()) {

            final EventType eventType = eventTypeRepository.findByName(eventTypeName);
            final Timeline timeline = timelineService.getTimeline(eventType);
            final String partitionsPath = format(PATH_ZK_PARTITIONS, subscriptionId, timeline.getTopic());
            try {
                final List<String> partitions = zkHolder.get().getChildren().forPath(partitionsPath);

                final List<NakadiCursor> eventTypeCursors = partitions.stream()
                        .map(partition -> readCursor(subscriptionId, timeline, partition))
                        .collect(Collectors.toList());

                cursorsListBuilder.addAll(eventTypeCursors);
            } catch (final KeeperException.NoNodeException nne) {
                LOG.debug(nne.getMessage(), nne);
                return Collections.emptyList();
            } catch (final Exception e) {
                LOG.error(e.getMessage(), e);
                throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
            }
        }
        return cursorsListBuilder.build();
    }

    private NakadiCursor readCursor(final String subscriptionId, final Timeline timeline, final String partition)
            throws RuntimeException {
        try {
            final String offsetPath = format(PATH_ZK_OFFSET, subscriptionId, timeline.getTopic(), partition);
            return new NakadiCursor(
                    timeline,
                    partition,
                    new String(zkHolder.get().getData().forPath(offsetPath), Charsets.UTF_8));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void resetCursors(final String subscriptionId, final List<NakadiCursor> cursors, final Client client)
            throws ServiceUnavailableException, NoSuchSubscriptionException,
            UnableProcessException, IllegalScopeException, OperationTimeoutException, ZookeeperException {
        if (cursors.isEmpty()) {
            throw new UnableProcessException("Cursors are absent");
        }
        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        validateSubscriptionCursors(subscription, cursors);
        validateSubscriptionReadScopes(subscription, client);

        final ZkSubscriptionClient zkClient = new CuratorZkSubscriptionClient(subscriptionId, zkHolder.get());
        // add 1 second to commit timeout in order to give time to finish reset if there is uncommitted events
        final long timeout = TimeUnit.SECONDS.toMillis(nakadiSettings.getDefaultCommitTimeoutSeconds()) +
                TimeUnit.SECONDS.toMillis(1);
        zkClient.resetCursors(cursors, timeout);
    }

    private void validateSubscriptionCursors(final Subscription subscription, final List<NakadiCursor> cursors)
            throws ServiceUnavailableException, NoSuchSubscriptionException, UnableProcessException {
        final List<String> wrongEventTypes = cursors.stream()
                .map(NakadiCursor::getEventType)
                .filter(et -> !subscription.getEventTypes().contains(et))
                .collect(Collectors.toList());
        if (!wrongEventTypes.isEmpty()) {
            throw new UnableProcessException("Event type does not belong to subscription: " + wrongEventTypes);
        }

        cursors.stream().forEach(cursor -> {
                    try {
                        timelineService.getTopicRepository(cursor.getTimeline()).validateCommitCursor(cursor);
                    } catch (final InvalidCursorException e) {
                        throw new UnableProcessException(e.getMessage(), e);
                    }});

        LOG.debug("[COMMIT_CURSORS] finished validation of {} cursor(s) for partition {} {}", cursors.size(),
                cursors.get(0).getTimeline().getEventType(), cursors.get(0).getTopic());
    }

    private void validateSubscriptionReadScopes(final Subscription subscription, final Client client)
            throws ServiceUnavailableException, NoSuchSubscriptionException, IllegalScopeException {
        subscription.getEventTypes().stream().map(Try.wrap(eventTypeRepository::findByName))
                .map(Try::getOrThrow)
                .forEach(eventType -> client.checkScopes(eventType.getReadScopes()));
    }

}
