package com.sdone.submitdailyptw.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.sdone.submitdailyptw.entity.WizardEnum;
import com.sdone.submitdailyptw.entity.WizardStatusEnum;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class CreateDailyPtw {


    @NotBlank(message = "token can't be blank")
    private String token;

    @NotNull(message = "wizardNo can't null")
    private WizardEnum wizardNo;

    @NotNull(message = "wizardStatus can't null")
    private WizardStatusEnum wizardStatus;

    private String uuid;

    @NotNull(message = "dataDaily can't null")
    private JsonNode dataDaily;

    @NotBlank(message = "tanggalDaily can't be blank")
    private String tanggalDaily;
}
