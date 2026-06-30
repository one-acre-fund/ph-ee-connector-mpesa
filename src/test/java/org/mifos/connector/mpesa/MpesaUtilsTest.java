package org.mifos.connector.mpesa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.common.gsma.dto.CustomData;
import org.mifos.connector.common.gsma.dto.GsmaTransfer;
import org.mifos.connector.mpesa.dto.PaybillResponseDTO;
import org.mifos.connector.mpesa.utility.MpesaUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MpesaUtilsTest {

    private MpesaUtils mpesaUtils;

    @BeforeEach
    void setUp() {
        mpesaUtils = new MpesaUtils();
    }

    @Test
    void testCreateGsmaTransferDTO_CustomDataContainsPaymentScheme() {
        PaybillResponseDTO paybillResponseDTO = new PaybillResponseDTO();
        paybillResponseDTO.setMsisdn("254700000000");
        paybillResponseDTO.setTransactionId("TX123");
        paybillResponseDTO.setAmount("100");
        paybillResponseDTO.setCurrency("KES");
        paybillResponseDTO.setAmsName("paygops");
        paybillResponseDTO.setAccountHoldingInstitutionId("tenant1");
        paybillResponseDTO.setReconciled(true);

        String clientCorrelationId = "corr-123";
        String businessShortCode = "shortcode";

        GsmaTransfer gsmaTransfer = mpesaUtils.createGsmaTransferDTO(paybillResponseDTO, clientCorrelationId, businessShortCode);

        List<CustomData> customDataList = gsmaTransfer.getCustomData();
        assertNotNull(customDataList);

        boolean foundPaymentScheme = customDataList.stream()
                .anyMatch(cd -> "paymentScheme".equals(cd.getKey()));

        assertTrue(foundPaymentScheme, "CustomData should contain paymentScheme key");
    }
}
