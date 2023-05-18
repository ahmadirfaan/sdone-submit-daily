package com.sdone.submitdailyptw.repository;

import com.sdone.submitdailyptw.entity.PtwData;
import com.sdone.submitdailyptw.entity.WizardEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PtwDataRepository extends JpaRepository<PtwData, String> {

    List<PtwData> findByUuidAndAndWizardAndLocalDate(String uuid, WizardEnum wizardEnum, LocalDate localDate);

    List<PtwData> findByUuid(String uuid);

}
