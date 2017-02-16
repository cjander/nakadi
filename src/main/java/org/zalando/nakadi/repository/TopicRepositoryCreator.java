package org.zalando.nakadi.repository;

import org.zalando.nakadi.domain.NakadiCursor;
import org.zalando.nakadi.domain.Storage;
import org.zalando.nakadi.domain.Timeline;
import org.zalando.nakadi.exceptions.NakadiRuntimeException;
import org.zalando.nakadi.exceptions.TopicRepositoryException;

import java.util.List;

public interface TopicRepositoryCreator {

    public TopicRepository createTopicRepository(Storage storage) throws TopicRepositoryException;

    public Timeline.StoragePosition createStoragePosition(List<NakadiCursor> offsets) throws NakadiRuntimeException;
}