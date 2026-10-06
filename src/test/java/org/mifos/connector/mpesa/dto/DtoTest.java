package org.mifos.connector.mpesa.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mifos.connector.common.gsma.dto.CustomData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoTest {

    @Test
    void paybillRequestDTO_shouldExposeAllFields() {
        PaybillRequestDTO dto = new PaybillRequestDTO("Pay Bill", "RKTQDM7W6S", "20191122063845", "10", "600638",
                "A123", "INV", "49197.00", "3P", "254708374149", "John");

        assertEquals("Pay Bill", dto.getTransactionType());
        assertEquals("RKTQDM7W6S", dto.getTransactionID());
        assertEquals("20191122063845", dto.getTransactionTime());
        assertEquals("10", dto.getTransactionAmount());
        assertEquals("600638", dto.getShortCode());
        assertEquals("A123", dto.getBillRefNo());
        assertEquals("INV", dto.getInvoiceNumber());
        assertEquals("49197.00", dto.getAccountBalance());
        assertEquals("3P", dto.getThirdPatrytransactionID());
        assertEquals("254708374149", dto.getMsisdn());
        assertEquals("John", dto.getFirstname());
        assertTrue(dto.toString().contains("transactionID='RKTQDM7W6S'"));
        assertTrue(dto.toString().contains("firstname='John'"));
    }

    @Test
    void buyGoodsPaymentRequestDTO_toStringShouldMaskPassword() {
        BuyGoodsPaymentRequestDTO dto = new BuyGoodsPaymentRequestDTO();
        dto.setBusinessShortCode(174379L);
        dto.setPassword("supersecret1234");
        dto.setTimestamp("20261005094948");
        dto.setTransactionType("CustomerBuyGoodsOnline");
        dto.setAmount(1L);
        dto.setPartyA(254708374149L);
        dto.setPartyB(174379L);
        dto.setPhoneNumber(254708374149L);
        dto.setCallBackURL("https://cb");
        dto.setAccountReference("ref");
        dto.setTransactionDesc("desc");

        String text = dto.toString();

        assertFalse(text.contains("supersecret"));
        assertTrue(text.contains("1234"));
        assertTrue(text.contains("callBackURL='https://cb'"));
        assertEquals("ref", dto.getAccountReference());
        assertEquals("desc", dto.getTransactionDesc());
    }

    @Test
    void transactionStatusRequestDTO_shouldExposeFields() {
        TransactionStatusRequestDTO dto = new TransactionStatusRequestDTO();
        dto.setBusinessShortCode(174379L);
        dto.setPassword("pwd");
        dto.setTimestamp("20261005094948");
        dto.setCheckoutRequestId("ws_CO_1");

        assertEquals(174379L, dto.getBusinessShortCode());
        assertEquals("pwd", dto.getPassword());
        assertEquals("20261005094948", dto.getTimestamp());
        assertEquals("ws_CO_1", dto.getCheckoutRequestId());
        assertTrue(dto.toString().contains("checkoutRequestId='ws_CO_1'"));
    }

    @Test
    void stkCallback_shouldExposeFields() {
        StkCallback callback = new StkCallback();
        callback.setMerchantRequestId("m-1");
        callback.setCheckoutRequestId("ws_CO_1");
        callback.setResultCode(1032L);
        callback.setResultDesc("Request cancelled by user.");

        assertEquals("m-1", callback.getMerchantRequestId());
        assertTrue(callback.toString().contains("resultCode=1032"));
    }

    @Test
    void channelSettlementRequestDTO_shouldExposeFields() {
        JSONObject payer = new JSONObject().put("a", 1);
        JSONObject payee = new JSONObject().put("b", 2);
        JSONObject amount = new JSONObject().put("amount", "10");
        ChannelSettlementRequestDTO dto = new ChannelSettlementRequestDTO();
        dto.setPayer(payer);
        dto.setPayee(payee);
        dto.setAmount(amount);

        assertSame(payer, dto.getPayer());
        assertSame(payee, dto.getPayee());
        assertSame(amount, dto.getAmount());
        assertEquals("{payer:{\"a\":1}, payee:{\"b\":2}, amount:{\"amount\":\"10\"}}", dto.toString());
    }

    @Test
    void channelRequestDTO_shouldExposeFields() {
        CustomData primary = new CustomData("foundationalId", "A123");
        CustomData secondary = new CustomData("MSISDN", "254708374149");
        ChannelRequestDTO dto = new ChannelRequestDTO(primary, secondary, List.of());

        assertSame(primary, dto.getPrimaryIdentifier());
        assertSame(secondary, dto.getSecondaryIdentifier());
        assertTrue(dto.toString().contains("customData=[]"));
    }

    @Test
    void errorCode_shouldExposeFields() {
        ErrorCode errorCode = new ErrorCode();
        errorCode.setId(7);
        errorCode.setTransactionType("collection");
        errorCode.setErrorMessage("Request cancelled by user.");
        errorCode.setErrorCode("1032");
        errorCode.setRecoverable(false);

        assertEquals(7, errorCode.getId());
        assertEquals("collection", errorCode.getTransactionType());
        assertEquals("Request cancelled by user.", errorCode.getErrorMessage());
        assertEquals("1032", errorCode.getErrorCode());
        assertFalse(errorCode.isRecoverable());
    }

    @Test
    void errorCode_shouldIgnoreUnknownFieldsFromOperations() throws Exception {
        String json = "[{\"id\":3,\"createdDate\":\"2026-10-06T14:46:18\",\"transactionType\":\"collection\","
                + "\"errorCode\":\"1037\",\"errorMessage\":\"DS timeout\",\"recoverable\":true}]";

        List<ErrorCode> codes = new ObjectMapper().readValue(json, new TypeReference<List<ErrorCode>>() {});

        assertEquals(1, codes.size());
        assertEquals("1037", codes.get(0).getErrorCode());
        assertTrue(codes.get(0).isRecoverable());
    }

    @Test
    void paybillResponseDTO_shouldExposeFields() {
        PaybillResponseDTO dto = new PaybillResponseDTO();
        dto.setReconciled(true);
        dto.setAmsName("roster");

        assertTrue(dto.isReconciled());
        assertTrue(dto.toString().contains("amsName=roster"));
    }
}
