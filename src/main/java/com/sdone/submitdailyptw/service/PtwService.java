package com.sdone.submitdailyptw.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdone.submitdailyptw.entity.ApprovalData;
import com.sdone.submitdailyptw.entity.PtwData;
import com.sdone.submitdailyptw.entity.WizardEnum;
import com.sdone.submitdailyptw.entity.WizardStatusEnum;
import com.sdone.submitdailyptw.entity.wizard6.WizardEnam;
import com.sdone.submitdailyptw.entity.wizard7.WizardTujuh;
import com.sdone.submitdailyptw.entity.wizard8.WizardDelapan;
import com.sdone.submitdailyptw.exception.BadRequestException;
import com.sdone.submitdailyptw.grpc.client.Ptw130Client;
import com.sdone.submitdailyptw.grpc.client.TokenValidatorServiceClient;
import com.sdone.submitdailyptw.model.CreateDailyPtw;
import com.sdone.submitdailyptw.model.FieldConstant;
import com.sdone.submitdailyptw.repository.ApprovalDataRepository;
import com.sdone.submitdailyptw.repository.PtwDataRepository;
import net.sumdev.projectone.database.ptw130.Enum130;
import net.sumdev.projectone.database.ptw130.Ptw130Wizard6;
import net.sumdev.projectone.database.ptw130.Ptw130Wizard7;
import net.sumdev.projectone.database.ptw130.Ptw130Wizard8;
import net.sumdev.projectone.database.user.UserOuterClass.UserWithRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.sumdev.projectone.database.ptw130.Ptw130.CreateDailyRequest;
import static net.sumdev.projectone.database.ptw130.Ptw130.DataDaily;
import static net.sumdev.projectone.database.user.UserOuterClass.Role;
import static net.sumdev.projectone.security.TokenValidator.ValidateResponse.Status;

@Service
public class PtwService {

    @Value("${com.sdone.createdailyPtw.isTestMode}")
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


    public Map<String, Object> createDailyPtw(CreateDailyPtw request) {

        var result = new HashMap<String, Object>();

        LocalDate localDate = validateTanggalDaily(request.getTanggalDaily());

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

            var approvalData = approvalDataRepository.findByUuid(request.getUuid());
            checkApprovalData(approvalData);
            var isError = true;
            List<WizardEnum> wizardEnumList = List.of(WizardEnum.WIZARD_6, WizardEnum.WIZARD_7);
            if (request.getWizardStatus() == WizardStatusEnum.DRAFT && request.getWizardNo() == WizardEnum.WIZARD_6) {
                isError = false;
            }

            if (request.getWizardStatus() == WizardStatusEnum.SUBMIT && request.getWizardNo() ==  WizardEnum.WIZARD_7) {
                isError = false;
                var wizard = ptwDataRepository.findByUuidAndAndWizardAndLocalDate(request.getUuid(), WizardEnum.WIZARD_6, localDate);
                if (wizard.isEmpty()) {
                    throw new BadRequestException(WizardEnum.WIZARD_6 + " is not exist");
                }

                wizard.forEach(ptwData -> {
                    ptwData.setStatus(WizardStatusEnum.SUBMIT);
                    ptwDataRepository.save(ptwData);
                });
            }

            if ((request.getWizardStatus() == WizardStatusEnum.APPROVE || request.getWizardStatus() == WizardStatusEnum.DECLINE)
                    && request.getWizardNo() ==  WizardEnum.WIZARD_8) {
                isError = false;
                wizardEnumList.forEach(wizardEnum -> {
                    var wizard = ptwDataRepository.findByUuidAndAndWizardAndLocalDate(request.getUuid(), wizardEnum, localDate);
                    if (wizard.isEmpty()) {
                        throw new BadRequestException(wizardEnum.name() + " is not exist");
                    }

                    wizard.forEach(ptwData -> {
                        ptwData.setStatus(request.getWizardStatus());
                        ptwDataRepository.save(ptwData);
                    });
                });
            }

            if (isError) {
                throw new BadRequestException("Not valid wizardNo and wizardStatus");
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
            try {
                var createDailyRequest = createRequest(request, validateToken.getUserWithRoles());
                if (createDailyRequest == null) {
                    throw new BadRequestException("Invalid WizardNo");
                }
                var dailyPtw = ptw130Client.createDailyPtw(createDailyRequest);
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
        if(tanggalDaily.length() != 8) {
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

    private void populateDbH2(CreateDailyPtw request, UserWithRoles userWithRoles, LocalDate date) throws JsonProcessingException {
        PtwData ptwData = new PtwData();
        long epochSecond = Instant.now().getEpochSecond();
        ptwData.setTimestamp(epochSecond);
        ptwData.setWizard(request.getWizardNo());
        ptwData.setStatus(request.getWizardStatus());
        ptwData.setUuid(request.getUuid());
        ptwData.setLocalDate(date);
        ptwData.setUsername(userWithRoles.getUsername());
        switch (request.getWizardNo()) {
            case WIZARD_6:
                var wizardEnam = objectMapper.readValue(request.getDataDaily().toString(), WizardEnam.class);
                var wizardEnamData = objectMapper.writeValueAsString(wizardEnam);
                ptwData.setData(wizardEnamData);
                break;
            case WIZARD_7:
                var wizardTujuh = objectMapper.readValue(request.getDataDaily().toString(), WizardTujuh.class);
                var wizardTujuhData = objectMapper.writeValueAsString(wizardTujuh);
                ptwData.setData(wizardTujuhData);
                break;
            case WIZARD_8:
                var wizardDelapan = objectMapper.readValue(request.getDataDaily().toString(), WizardDelapan.class);
                var wizardDelapanData = objectMapper.writeValueAsString(wizardDelapan);
                ptwData.setData(wizardDelapanData);
                break;
            default:
                throw new BadRequestException("Invalid data PTW");
        }

        ptwDataRepository.save(ptwData);
    }

    private CreateDailyRequest createRequest(CreateDailyPtw request, UserWithRoles userWithRoles) throws JsonProcessingException {
        long epochSecond = Instant.now().getEpochSecond();
        String uuid = request.getUuid();
        String username = userWithRoles.getUsername();
        var jsonNode = request.getDataDaily();
        switch (request.getWizardNo()) {
            case WIZARD_6:
                WizardEnam wizardEnam = objectMapper.readValue(jsonNode.toString(), WizardEnam.class);
                return CreateDailyRequest
                        .newBuilder()
                        .setTimestamp(epochSecond)
                        .setUuid(uuid)
                        .setUsername(username)
                        .setWizardNo(Enum130.WizardNo.WIZARD_6)
                        .setDataDaily(DataDaily.newBuilder()
                                .setWizardEnam(Ptw130Wizard6.DataPtwWizardEnam.newBuilder()
                                        .setTimestamp(epochSecond)
                                        .setUsername(username)
                                        .setUuid(uuid)
                                        .setImplementasi(Ptw130Wizard6.Implementasi.newBuilder()
                                                .setTanggalPtw(wizardEnam.getImplementasi().getTanggalPtw())
                                                .setTitikAksesLokasi(wizardEnam.getImplementasi().getTitikAksesLokasi())
                                                .setJumlahPekerja(wizardEnam.getImplementasi().getJumlahPekerja())
                                                .addAllFotoPekerjaan(wizardEnam.getImplementasi().getFotoPekerjaan())
                                                .build())
                                        .build())
                                .build())
                        .build();
            case WIZARD_7:
                var wizardTujuh = objectMapper.readValue(jsonNode.toString(), WizardTujuh.class);
                return CreateDailyRequest
                        .newBuilder()
                        .setTimestamp(epochSecond)
                        .setUuid(uuid)
                        .setUsername(username)
                        .setWizardNo(Enum130.WizardNo.WIZARD_7)
                        .setDataDaily(DataDaily.newBuilder()
                                .setWizardTujuh(Ptw130Wizard7.DataPtwWizardTujuh.newBuilder()
                                        .setTimestamp(epochSecond)
                                        .setUsername(username)
                                        .setUuid(uuid)
                                        .setSafety(Ptw130Wizard7.WorksiteSafety.newBuilder()
                                                .setSortCircuit(wizardTujuh.getSafety().getSortCircuit())
                                                .setMarkerBoards(wizardTujuh.getSafety().getMarkerBoards())
                                                .setEquipmentIsolation(wizardTujuh.getSafety().getEquipmentIsolation())
                                                .setPengaturanKomunikasi(wizardTujuh.getSafety().getPengaturanKomunikasi())
                                                .setTitikKeluarDarurat(wizardTujuh.getSafety().getTitikKeluarDarurat())
                                                .build())
                                        .build())
                                .build())
                        .build();
            case WIZARD_8:
                var wizardDelapan = objectMapper.readValue(jsonNode.toString(), WizardDelapan.class);
                return CreateDailyRequest
                        .newBuilder()
                        .setTimestamp(epochSecond)
                        .setUuid(uuid)
                        .setUsername(username)
                        .setWizardNo(Enum130.WizardNo.WIZARD_8)
                        .setDataDaily(DataDaily.newBuilder()
                                .setWizardDelapan(Ptw130Wizard8.DataPtwWizardDelapan.newBuilder()
                                        .setTimestamp(epochSecond)
                                        .setUsername(username)
                                        .setUuid(uuid)
                                        .setOtorisasi(Ptw130Wizard8.Otorisasi.newBuilder()
                                                .setOccLineOperator(wizardDelapan.getOtorisasi().getOccLineOperator())
                                                .setOccUsername(wizardDelapan.getOtorisasi().getOccUsername())
                                                .setAksesDiberikan(wizardDelapan.getOtorisasi().getAksesDiberikan())
                                                .setAlasanTolak(wizardDelapan.getOtorisasi().getAlasanTolak())
                                                .addAllStasiunOperator(wizardDelapan.getOtorisasi().getStasiunOperator())
                                                .build())
                                        .build())
                                .build())
                        .build();
            default:
                return null;
        }
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

    private void checkApprovalData(List<ApprovalData> approvalData) {
        var approvalDataVp = approvalData.stream().filter(data -> data.getStatus().equalsIgnoreCase(WizardStatusEnum.APPROVE.toString())
                && data.getRole().equalsIgnoreCase("VP")).findAny();
        var approvalDataJm = approvalData.stream().filter(data -> data.getStatus().equalsIgnoreCase(WizardStatusEnum.APPROVE.toString())
                && data.getRole().equalsIgnoreCase("JM")).findAny();
        var approvalDataShe = approvalData.stream().filter(data -> data.getStatus().equalsIgnoreCase(WizardStatusEnum.APPROVE.toString())
                && data.getRole().equalsIgnoreCase("SHE")).findAny();

        if (approvalDataVp.isEmpty() || approvalDataJm.isEmpty() || approvalDataShe.isEmpty()) {

            throw new BadRequestException("Approval is not exist ");
        }
    }
}

