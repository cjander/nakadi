package org.zalando.nakadi.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.zalando.nakadi.exceptions.ConflictException;
import org.zalando.nakadi.exceptions.ForbiddenAccessException;
import org.zalando.nakadi.exceptions.NotFoundException;
import org.zalando.nakadi.exceptions.UnableProcessException;
import org.zalando.nakadi.security.Client;
import org.zalando.nakadi.service.timeline.TimelineService;
import org.zalando.nakadi.view.TimelineRequest;
import org.zalando.nakadi.view.TimelineView;
import org.zalando.problem.MoreStatus;
import org.zalando.problem.Problem;
import org.zalando.problem.spring.web.advice.Responses;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = "/event-types/{name}/timelines", produces = MediaType.APPLICATION_JSON)
public class TimelinesController {

    private static final Logger LOG = LoggerFactory.getLogger(TimelinesController.class);

    private final TimelineService timelineService;

    @Autowired
    public TimelinesController(final TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<?> createTimeline(@PathVariable("name") final String eventTypeName,
                                            @RequestBody final TimelineRequest timelineRequest, final Client client) {
        timelineService.createTimeline(eventTypeName, timelineRequest.getStorageId(), client);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @RequestMapping(value = "{id}", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteTimeline(@PathVariable("name") final String eventTypeName,
                                            @PathVariable("id") final String timelineId, final Client client) {
        timelineService.delete(eventTypeName, timelineId, client);
        return ResponseEntity.ok().build();
    }

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<?> getTimelines(@PathVariable("name") final String eventTypeName, final Client client) {
        return ResponseEntity.ok(timelineService.getTimelines(eventTypeName, client).stream()
                .map(TimelineView::new)
                .collect(Collectors.toList()));
    }

    @ExceptionHandler(ForbiddenAccessException.class)
    public ResponseEntity<Problem> forbidden(final ForbiddenAccessException ex, final NativeWebRequest request) {
        LOG.error(ex.getMessage(), ex);
        return Responses.create(Response.Status.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler(UnableProcessException.class)
    public ResponseEntity<Problem> unprocessable(final UnableProcessException ex, final NativeWebRequest request) {
        LOG.error(ex.getMessage(), ex);
        return Responses.create(MoreStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Problem> notFound(final NotFoundException ex, final NativeWebRequest request) {
        LOG.error(ex.getMessage(), ex);
        return Responses.create(Response.Status.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Problem> conflict(final ConflictException ex, final NativeWebRequest request) {
        LOG.debug(ex.getMessage(), ex);
        return Responses.create(Response.Status.CONFLICT, ex.getMessage(), request);
    }

}
