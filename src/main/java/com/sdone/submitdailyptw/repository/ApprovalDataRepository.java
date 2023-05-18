package com.sdone.submitdailyptw.repository;

import com.sdone.submitdailyptw.entity.ApprovalData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalDataRepository extends JpaRepository<ApprovalData, String> {

    List<ApprovalData> findByUuid(String uuid);
}
