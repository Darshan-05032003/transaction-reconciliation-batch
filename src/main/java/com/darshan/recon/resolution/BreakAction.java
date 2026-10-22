package com.darshan.recon.resolution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recon_break_action")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreakAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "break_id", nullable = false, updatable = false)
    private long breakId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private BreakActionType action;

    @Column(name = "performed_by", nullable = false, updatable = false, length = 100)
    private String performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private OffsetDateTime performedAt;

    @Column(nullable = false, updatable = false, length = 500)
    private String comment;

    public BreakAction(long breakId, BreakActionType action, String performedBy, OffsetDateTime performedAt,
                       String comment) {
        this.breakId = breakId;
        this.action = action;
        this.performedBy = performedBy;
        this.performedAt = performedAt;
        this.comment = comment;
    }
}
