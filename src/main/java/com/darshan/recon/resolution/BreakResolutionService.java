package com.darshan.recon.resolution;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.common.exception.NotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BreakResolutionService {

    private static final Logger log = LoggerFactory.getLogger(BreakResolutionService.class);

    private final ReconBreakRepository breaks;
    private final BreakActionRepository actions;
    private final ReconProperties properties;
    private final Clock clock;

    public BreakResolutionService(ReconBreakRepository breaks, BreakActionRepository actions,
                                  ReconProperties properties, Clock clock) {
        this.breaks = breaks;
        this.actions = actions;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BreakView get(long breakId) {
        return view(require(breakId));
    }

    // A write-off whose exposure is above the configured threshold is only requested here;
    // it takes a second user with the APPROVER role to make it final.
    public BreakView resolve(long breakId, ResolutionAction action, String comment, String user) {
        ReconBreak reconBreak = require(breakId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        BreakActionType performed = switch (action) {
            case RESOLVED -> {
                reconBreak.resolve(user, comment, now);
                yield BreakActionType.RESOLVE;
            }
            case WRITTEN_OFF -> {
                if (reconBreak.needsApproval(properties.writeOffApprovalThreshold())) {
                    reconBreak.requestWriteOff(user, comment, now);
                    yield BreakActionType.REQUEST_WRITE_OFF;
                }
                reconBreak.writeOff(user, comment, now);
                yield BreakActionType.WRITE_OFF;
            }
        };
        return record(reconBreak, performed, user, comment, now);
    }

    public BreakView approve(long breakId, String comment, String approver) {
        ReconBreak reconBreak = require(breakId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        reconBreak.approveWriteOff(approver, now);
        return record(reconBreak, BreakActionType.APPROVE_WRITE_OFF, approver, comment, now);
    }

    public BreakView reject(long breakId, String comment, String approver) {
        ReconBreak reconBreak = require(breakId);
        reconBreak.rejectWriteOff();
        return record(reconBreak, BreakActionType.REJECT_WRITE_OFF, approver, comment, OffsetDateTime.now(clock));
    }

    private BreakView record(ReconBreak reconBreak, BreakActionType action, String user, String comment,
                             OffsetDateTime at) {
        actions.save(new BreakAction(reconBreak.getId(), action, user, at, comment));
        log.info("Break {} {} by {}: status is now {}", reconBreak.getId(), action, user, reconBreak.getStatus());
        return view(reconBreak);
    }

    private ReconBreak require(long breakId) {
        return breaks.findById(breakId).orElseThrow(() -> new NotFoundException("Break", breakId));
    }

    private BreakView view(ReconBreak reconBreak) {
        return BreakView.of(reconBreak, actions.findByBreakIdOrderByIdAsc(reconBreak.getId()));
    }
}
