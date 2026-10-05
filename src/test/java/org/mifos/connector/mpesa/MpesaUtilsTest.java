package org.mifos.connector.mpesa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.common.gsma.dto.CustomData;
import org.mifos.connector.common.gsma.dto.GsmaTransfer;
import org.mifos.connector.mpesa.dto.PaybillResponseDTO;
import org.mifos.connector.mpesa.dto.ChannelRequestDTO;
import org.mifos.connector.mpesa.dto.ChannelSettlementRequestDTO;
import org.mifos.connector.mpesa.dto.PaybillRequestDTO;
import org.mifos.connector.mpesa.utility.MpesaAMSProp;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void createGsmaTransferDTO_shouldMapPartiesAndReconciliation() {
        PaybillResponseDTO response = new PaybillResponseDTO(true, "roster", "kenya", "RKTQDM7W6S", "10", "KES",
                "254708374149");

        GsmaTransfer transfer = mpesaUtils.createGsmaTransferDTO(response, "corr-1", "174379");

        assertEquals("254708374149", transfer.getPayer().get(0).getPartyIdIdentifier());
        assertEquals("accountId", transfer.getPayee().get(0).getPartyIdType());
        assertEquals("RKTQDM7W6S", transfer.getRequestingOrganisationTransactionReference());
        assertEquals("10", transfer.getAmount());
        assertEquals("KES", transfer.getCurrency());
        assertEquals(false, customValue(transfer.getCustomData(), "partyLookupFailed"));
        assertEquals("corr-1", customValue(transfer.getCustomData(), "clientCorrelationId"));
        assertEquals("174379", customValue(transfer.getCustomData(), "initiatorFspId"));
    }

    @Test
    void convertPaybillToChannelPayload_shouldMapPayeeIdentifierPerAms() {
        PaybillRequestDTO request = paybillRequest();

        assertEquals("FOUNDATIONALID", payeeType(mpesaUtils.convertPaybillToChannelPayload(request, "paygops", "KES")));
        assertEquals("ACCOUNTID", payeeType(mpesaUtils.convertPaybillToChannelPayload(request, "roster", "KES")));
        assertEquals("FINERACTACCOUNTID", payeeType(mpesaUtils.convertPaybillToChannelPayload(request, "fineract", "KES")));

        ChannelSettlementRequestDTO unknown = mpesaUtils.convertPaybillToChannelPayload(request, "unknown", "KES");
        assertTrue(unknown.getPayee().getJSONObject("partyIdInfo").isEmpty());
        assertEquals("254708374149", unknown.getPayer().getJSONObject("partyIdInfo").getString("partyIdentifier"));
        assertEquals("10", unknown.getAmount().getString("amount"));
        assertEquals("KES", unknown.getAmount().getString("currency"));
    }

    @Test
    void convertPaybillPayloadToChannelPayload_shouldMapPrimaryIdentifierPerAms() {
        PaybillRequestDTO request = paybillRequest();

        ChannelRequestDTO paygops = MpesaUtils.convertPaybillPayloadToChannelPayload(request, "paygops", "KES");
        assertEquals("foundationalId", paygops.getPrimaryIdentifier().getKey());
        assertEquals("A123", paygops.getPrimaryIdentifier().getValue());
        assertEquals("MSISDN", paygops.getSecondaryIdentifier().getKey());
        assertEquals(5, paygops.getCustomData().size());

        assertEquals("accountID",
                MpesaUtils.convertPaybillPayloadToChannelPayload(request, "roster", "KES").getPrimaryIdentifier().getKey());
        assertEquals("FINERACTACCOUNTID",
                MpesaUtils.convertPaybillPayloadToChannelPayload(request, "fineract", "KES").getPrimaryIdentifier().getKey());
        assertNull(MpesaUtils.convertPaybillPayloadToChannelPayload(request, "unknown", "KES").getPrimaryIdentifier().getKey());
    }

    @Test
    void getAMSUrl_shouldResolveConfiguredHosts() {
        ReflectionTestUtils.setField(mpesaUtils, "paygopsHost", "http://paygops");
        ReflectionTestUtils.setField(mpesaUtils, "rosterHost", "http://roster");
        ReflectionTestUtils.setField(mpesaUtils, "fineractHost", "http://fineract");

        assertEquals("http://paygops", mpesaUtils.getAMSUrl("paygops"));
        assertEquals("http://roster", mpesaUtils.getAMSUrl("roster"));
        assertEquals("http://fineract", mpesaUtils.getAMSUrl("fineract"));
        assertNull(mpesaUtils.getAMSUrl("unknown"));
    }

    @Test
    void setMpesaProperties_shouldPickGroupMatchingProcessElseDefault() {
        withGroups(group("default"), group("roster"));

        mpesaUtils.setProcess("mpesa_flow_roster-kenya");
        assertEquals("roster", mpesaUtils.setMpesaProperties().getName());
        assertEquals("mpesa_flow_roster-kenya", mpesaUtils.getProcess());

        mpesaUtils.setProcess("mpesa_flow_paygops-kenya");
        assertEquals("default", mpesaUtils.setMpesaProperties().getName());
    }

    @Test
    void setMpesaProperties_withoutDefault_shouldReturnNull() {
        withGroups(group("roster"));
        mpesaUtils.setProcess("mpesa_flow_paygops-kenya");

        assertNull(mpesaUtils.setMpesaProperties());
    }

    @Test
    void getMpesaProperties_shouldPickMatchingAmsElseDefault() {
        withGroups(group("default"), group("roster"));

        assertEquals("roster", mpesaUtils.getMpesaProperties("ROSTER", "tx-1").getName());
        assertEquals("default", mpesaUtils.getMpesaProperties("paygops", "tx-1").getName());
    }

    @Test
    void getMpesaProperties_withNoMatchAndNoDefault_shouldReturnNull() {
        withGroups(group("roster"));

        assertNull(mpesaUtils.getMpesaProperties("paygops", "tx-1"));
    }

    @Test
    void maskString_shouldMaskAllButLastFourCharacters() {
        assertEquals("********4149", MpesaUtils.maskString("254708374149"));
        assertEquals("abcd", MpesaUtils.maskString("abcd"));
        assertEquals("***", MpesaUtils.maskString("abc"));
        assertNull(MpesaUtils.maskString(null));
    }

    @Test
    void main_shouldRun() {
        MpesaUtils.main(new String[0]);
    }

    private void withGroups(MpesaProps.MPESA... groups) {
        MpesaAMSProp amsProp = mock(MpesaAMSProp.class);
        when(amsProp.getGroup()).thenReturn(List.of(groups));
        ReflectionTestUtils.setField(mpesaUtils, "mpesaAMSProp", amsProp);
    }

    private static MpesaProps.MPESA group(String name) {
        MpesaProps.MPESA group = new MpesaProps.MPESA();
        group.setName(name);
        return group;
    }

    private static PaybillRequestDTO paybillRequest() {
        return new PaybillRequestDTO("Pay Bill", "RKTQDM7W6S", "20191122063845", "10", "174379", "A123", "",
                "49197.00", "", "254708374149", "John");
    }

    private static String payeeType(ChannelSettlementRequestDTO dto) {
        return dto.getPayee().getJSONObject("partyIdInfo").getString("partyIdType");
    }

    private static Object customValue(List<CustomData> customData, String key) {
        return customData.stream().filter(c -> key.equals(c.getKey())).findFirst().orElseThrow().getValue();
    }
}
