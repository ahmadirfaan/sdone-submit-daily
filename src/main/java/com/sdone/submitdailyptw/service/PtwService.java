package com.sdone.submitdailyptw.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdone.submitdailyptw.entity.PtwData;
import com.sdone.submitdailyptw.entity.WizardEnum;
import com.sdone.submitdailyptw.entity.WizardStatusEnum;
import com.sdone.submitdailyptw.exception.BadRequestException;
import com.sdone.submitdailyptw.grpc.client.Ptw130Client;
import com.sdone.submitdailyptw.grpc.client.TokenValidatorServiceClient;
import com.sdone.submitdailyptw.model.FieldConstant;
import com.sdone.submitdailyptw.model.SubmitDailyPtw;
import com.sdone.submitdailyptw.repository.ApprovalDataRepository;
import com.sdone.submitdailyptw.repository.PtwDataRepository;
import net.sumdev.projectone.database.user.UserOuterClass.UserWithRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.sumdev.projectone.database.ptw130.Enum130.WizardNo;
import static net.sumdev.projectone.database.ptw130.Enum130.WizardStatus;
import static net.sumdev.projectone.database.ptw130.Ptw130.SubmitDaily;
import static net.sumdev.projectone.database.ptw130.Ptw130.SubmitDailyRequest;
import static net.sumdev.projectone.database.user.UserOuterClass.Role;
import static net.sumdev.projectone.security.TokenValidator.ValidateResponse.Status;

@Service
public class PtwService {

    @Value("${com.sdone.submitdailyPtw.isTestMode}")
    private boolean testMode;

    @Autowired
    private PtwDataRepository ptwDataRepository;

    @Autowired
    private TokenValidatorServiceClient tokenValidatorServiceClient;

    @Autowired
    private Ptw130Client ptw130Client;

    @Autowired
    private ApprovalDataRepository approvalDataRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();


    public Map<String, Object> submitDailyPtw(SubmitDailyPtw request) {

        var result = new HashMap<String, Object>();

        var localDate = validateTanggalDaily(request.getTanggalDaily());

        var statusSubmitted = List.of(WizardStatusEnum.DONE, WizardStatusEnum.COMPLETE);
        boolean canSubmitted = request.getWizardNo() == WizardEnum.WIZARD_9 &&
                statusSubmitted.contains(request.getWizardStatus());
        if (!canSubmitted) {
            throw new BadRequestException("Must be wizard 9");
        }

        var validateToken = tokenValidatorServiceClient.validateToken(request.getToken());
        if (validateToken.getStatus() != Status.VALID) {
            returnNotAuthorized(result);
            return result;
        }

        var isPermissionValid = checkPermissionRoles(validateToken.getUserWithRoles());
        if (!isPermissionValid) {
            returnNotAuthorized(result);
            return result;
        }

        if (testMode) {

            checkEligibleSubmit(request.getUuid(), localDate);

            if (request.getWizardStatus() == WizardStatusEnum.DONE) {
                ptwDataRepository.findByUuid(request.getUuid()).forEach(d -> {
                    d.setStatus(WizardStatusEnum.DONE);
                    ptwDataRepository.save(d);
                });

                approvalDataRepository.findByUuid(request.getUuid()).forEach(d -> {
                    d.setStatus(WizardStatusEnum.DONE.name());
                    approvalDataRepository.save(d);
                });
            } else if (request.getWizardStatus() == WizardStatusEnum.COMPLETE) {
                ptwDataRepository.findByUuidAndAndWizardAndLocalDate(request.getUuid(), WizardEnum.WIZARD_6, localDate).forEach(d -> {
                    d.setStatus(WizardStatusEnum.COMPLETE);
                    ptwDataRepository.save(d);
                });
                ptwDataRepository.findByUuidAndAndWizardAndLocalDate(request.getUuid(), WizardEnum.WIZARD_7, localDate).forEach(d -> {
                    d.setStatus(WizardStatusEnum.COMPLETE);
                    ptwDataRepository.save(d);
                });
                ptwDataRepository.findByUuidAndAndWizardAndLocalDate(request.getUuid(), WizardEnum.WIZARD_8, localDate).forEach(d -> {
                    d.setStatus(WizardStatusEnum.COMPLETE);
                    ptwDataRepository.save(d);
                });
            }



            try {
                populateDbH2(request, validateToken.getUserWithRoles(), localDate);
            } catch (JsonProcessingException e) {
                throw new BadRequestException("Invalid Data PTW : " + e.getMessage());
            }

            result.put(FieldConstant.HTTP_STATUS, HttpStatus.OK.value());
            result.put(FieldConstant.RESULT, "success put to DB");
            result.put("uuid", request.getUuid());
        } else {
            var wizardStatus = WizardStatus.forNumber(request.getWizardStatus().ordinal());
            try {
                var createDailyRequest = createRequest(request, validateToken.getUserWithRoles(), wizardStatus);
                var dailyPtw = ptw130Client.submitDailyPtw(createDailyRequest);
                result.put(FieldConstant.HTTP_STATUS, HttpStatus.OK.value());
                result.put(FieldConstant.RESULT, dailyPtw.getResult());
                result.put("uuid", dailyPtw.getUuid());
            } catch (JsonProcessingException e) {
                throw new BadRequestException("Invalid Data Ptw : " + e.getMessage());
            }
        }
        return result;
    }

    private LocalDate validateTanggalDaily(String tanggalDaily) {
        if (tanggalDaily.length() != 8) {
            throw new BadRequestException("tanggal daily request is not date format");
        }

        var year = tanggalDaily.substring(0, 4);
        var month = tanggalDaily.substring(4, 6);
        var date = tanggalDaily.substring(6, 8);
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(date));
        } catch (DateTimeException dateTimeException) {
            throw new BadRequestException("tanggal daily request is not date format");
        }
    }

    private void populateDbH2(SubmitDailyPtw request, UserWithRoles userWithRoles, LocalDate date) throws JsonProcessingException {
        PtwData ptwData = new PtwData();
        long epochSecond = Instant.now().getEpochSecond();
        ptwData.setTimestamp(epochSecond);
        ptwData.setWizard(request.getWizardNo());
        ptwData.setStatus(request.getWizardStatus());
        ptwData.setUuid(request.getUuid());
        ptwData.setLocalDate(date);
        ptwData.setUsername(userWithRoles.getUsername());
        ptwData.setData(request.getSubmitDaily().toString());
        ptwDataRepository.save(ptwData);
    }

    private SubmitDailyRequest createRequest(SubmitDailyPtw request, UserWithRoles userWithRoles, WizardStatus wizardStatus) throws JsonProcessingException {
        long epochSecond = Instant.now().getEpochSecond();
        String uuid = request.getUuid();
        String username = userWithRoles.getUsername();
        var jsonNode = request.getSubmitDaily();
        return SubmitDailyRequest.newBuilder()
                .setUsername(username)
                .setTimestamp(epochSecond)
                .setUuid(uuid)
                .setWizardNo(WizardNo.WIZARD_9)
                .setWizardStatus(wizardStatus)
                .setSubmitDaily(SubmitDaily.newBuilder()
                        .build())
                .build();
    }


    private static void returnNotAuthorized(HashMap<String, Object> result) {
        result.put(FieldConstant.HTTP_STATUS, HttpStatus.UNAUTHORIZED.value());
        result.put(FieldConstant.RESULT, "error");
    }


    private boolean checkPermissionRoles(UserWithRoles userWithRoles) {
        var isAllowedRole = false;
        var isAllowedPermission = false;
        for (int i = 0; i < userWithRoles.getRolesList().size(); i++) {
            Role roles = userWithRoles.getRoles(i);
            if (!isAllowedRole) {
                isAllowedRole = roles.getGroup().equalsIgnoreCase("MAINTENANCE");
            }
            for (String permission : roles.getPermissionList()) {
                if (!isAllowedPermission) {
                    isAllowedPermission = permission.equals("createPtw");
                }
            }

            if (isAllowedRole && isAllowedPermission) {
                break;
            }
        }
        return isAllowedRole && isAllowedPermission;
    }

    private void checkEligibleSubmit(String uuid, LocalDate date) {
        var result = new ArrayList<PtwData>();
        var wizardSix = ptwDataRepository.findByUuidAndAndWizardAndLocalDate(uuid, WizardEnum.WIZARD_6, date);
        result.addAll(wizardSix);
        var wizardSeven = ptwDataRepository.findByUuidAndAndWizardAndLocalDate(uuid, WizardEnum.WIZARD_7, date);
        result.addAll(wizardSeven);

        var wizardEight = ptwDataRepository.findByUuidAndAndWizardAndLocalDate(uuid, WizardEnum.WIZARD_8, date);
        result.addAll(wizardEight);

        result.forEach(data -> {
            var statusApproved = data.getStatus() != WizardStatusEnum.APPROVE;
            if (statusApproved) {
                throw new BadRequestException(
                        String.format("Data is not approved, uuid: %s, wizardNo : %s, wizardStatus: %s", data.getUuid(),
                                data.getWizard(), data.getStatus()));
            }
        });
    }
}

