package com.darshan.recon.resolution;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakActionRepository extends JpaRepository<BreakAction, Long> {

    List<BreakAction> findByBreakIdOrderByIdAsc(long breakId);
}
