package com.axon.core_service.repository;

import com.axon.core_service.domain.user.UserSummary;
import com.axon.core_service.domain.user.RfmSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserSummaryRepository extends JpaRepository<UserSummary, Long> {

    @Query("SELECT summary.userId FROM UserSummary summary " +
            "WHERE summary.userId IN :userIds AND summary.rfmSegment = :rfmSegment")
    List<Long> findUserIdsByUserIdInAndRfmSegment(
            @Param("userIds") List<Long> userIds,
            @Param("rfmSegment") RfmSegment rfmSegment);
}
