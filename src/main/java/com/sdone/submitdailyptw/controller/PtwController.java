package com.sdone.submitdailyptw.controller;

import com.sdone.submitdailyptw.model.SubmitDailyPtw;
import com.sdone.submitdailyptw.model.FieldConstant;
import com.sdone.submitdailyptw.service.PtwService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

@RestController
@CrossOrigin
public class PtwController {

    @Autowired
    private PtwService ptwService;

    @PostMapping("/v1/ptw/daily/submit")
    public ResponseEntity<Map<String, Object>> submitDailyPtw(@RequestBody @Valid SubmitDailyPtw createDailyPtw) {
        var result = ptwService.submitDailyPtw(createDailyPtw);
        var httpStatus = (Integer) result.get(FieldConstant.HTTP_STATUS);
        result.remove(FieldConstant.HTTP_STATUS);
        return ResponseEntity.status(httpStatus).body(result);
    }
}
