package org.mifos.connector.mpesa.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.common.gsma.dto.AccessTokenDTO;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthRoutesTest {

  @Mock private AccessTokenStore accessTokenStore;

  @Mock private MpesaUtils mpesaUtils;

  @InjectMocks private AuthRoutes authRoutes;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(authRoutes, "accessTokenStore", accessTokenStore);
    ReflectionTestUtils.setField(authRoutes, "mpesautils", mpesaUtils);
    ReflectionTestUtils.setField(authRoutes, "mpesaTimeout", 5000);
  }

  @Test
  void accessTokenError_shouldLogErrorAndSetErrorInformation() throws Exception {
    MpesaProps.MPESA mockMpesaProps = new MpesaProps.MPESA();
    mockMpesaProps.setName("TestMpesa");
    mockMpesaProps.setClientKey("testKey");
    mockMpesaProps.setClientSecret("testSecret");
    mockMpesaProps.setAuthHost("http://test-host");

    when(mpesaUtils.setMpesaProperties()).thenReturn(mockMpesaProps);

    CamelContext context = new DefaultCamelContext();
    context.addRoutes(authRoutes);
    context.start();

    try {
      Exchange exchange = new DefaultExchange(context);
      exchange.getIn().setBody("Error occurred");

      ProducerTemplate template = context.createProducerTemplate();
      template.send("direct:access-token-error", exchange);
      verify(mpesaUtils).setMpesaProperties();
      assertEquals("Error occurred", exchange.getProperty(ERROR_INFORMATION));
    } finally {
      context.stop();
    }
  }

  @Test
  void accessTokenSave_shouldSaveAccessTokenAndLogIt() throws Exception {
    MpesaProps.MPESA mockMpesaProps = new MpesaProps.MPESA();
    mockMpesaProps.setName("TestMpesa");
    mockMpesaProps.setClientKey("testKey");
    mockMpesaProps.setClientSecret("testSecret");
    mockMpesaProps.setAuthHost("http://test-host");

    when(mpesaUtils.setMpesaProperties()).thenReturn(mockMpesaProps);

    CamelContext context = new DefaultCamelContext();
    context.addRoutes(authRoutes);
    context.start();

    try {
      Exchange exchange = new DefaultExchange(context);
      exchange.getIn().setBody("{\"access_token\":\"test-token\",\"expires_in\":3600}");

      ProducerTemplate template = context.createProducerTemplate();
      template.send("direct:access-token-save", exchange);

      verify(accessTokenStore).saveToken("test-token", 3600);
      verify(mpesaUtils).setMpesaProperties();
    } finally {
      context.stop();
    }
  }

  @Test
  void getAccessToken_whenTokenValid_shouldSkipFetch() throws Exception {
    MpesaProps.MPESA mockMpesaProps = new MpesaProps.MPESA();
    mockMpesaProps.setName("TestMpesa");
    mockMpesaProps.setClientKey("testKey");
    mockMpesaProps.setClientSecret("testSecret");
    mockMpesaProps.setAuthHost("http://test-host");

    when(mpesaUtils.setMpesaProperties()).thenReturn(mockMpesaProps);
    when(accessTokenStore.isValid()).thenReturn(true);

    CamelContext context = new DefaultCamelContext();
    context.addRoutes(authRoutes);
    context.start();

    try {
      Exchange exchange = new DefaultExchange(context);
      context.createProducerTemplate().send("direct:get-access-token", exchange);

      verify(accessTokenStore).isValid();
      verify(accessTokenStore, never()).saveToken(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    } finally {
      context.stop();
    }
  }

  @Test
  void getAccessToken_whenExpired_shouldFetchAndSaveToken() throws Exception {
    CamelContext context = contextWithMockedTokenEndpoint(200, "{\"access_token\":\"new-token\",\"expires_in\":3599}");
    when(accessTokenStore.isValid()).thenReturn(false);

    try {
      Exchange exchange = context.createProducerTemplate().send("direct:get-access-token", e -> { });

      verify(accessTokenStore).saveToken("new-token", 3599);
      Exchange sent = context.getEndpoint("mock:token", org.apache.camel.component.mock.MockEndpoint.class)
          .getReceivedExchanges().get(0);
      assertEquals("GET", sent.getIn().getHeader(Exchange.HTTP_METHOD));
      assertEquals("grant_type=client_credentials", sent.getIn().getHeader(Exchange.HTTP_RAW_QUERY));
      org.junit.jupiter.api.Assertions.assertNull(exchange.getException());
    } finally {
      context.stop();
    }
  }

  @Test
  void getAccessToken_whenFetchFails_shouldRecordError() throws Exception {
    CamelContext context = contextWithMockedTokenEndpoint(401, "Unauthorized");
    when(accessTokenStore.isValid()).thenReturn(false);

    try {
      Exchange exchange = context.createProducerTemplate().send("direct:get-access-token", e -> { });

      verify(accessTokenStore, never()).saveToken(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
      assertEquals("Unauthorized", exchange.getProperty(ERROR_INFORMATION));
    } finally {
      context.stop();
    }
  }

  private CamelContext contextWithMockedTokenEndpoint(int status, String body) throws Exception {
    MpesaProps.MPESA mockMpesaProps = new MpesaProps.MPESA();
    mockMpesaProps.setName("TestMpesa");
    mockMpesaProps.setClientKey("testKey");
    mockMpesaProps.setClientSecret("testSecret");
    mockMpesaProps.setAuthHost("http://test-host");
    when(mpesaUtils.setMpesaProperties()).thenReturn(mockMpesaProps);

    CamelContext context = new DefaultCamelContext();
    context.addRoutes(authRoutes);
    org.apache.camel.builder.AdviceWith.adviceWith(context, "access-token-fetch",
        a -> a.weaveByToString("DynamicTo.*").replace().to("mock:token"));
    context.start();
    context.getEndpoint("mock:token", org.apache.camel.component.mock.MockEndpoint.class)
        .whenAnyExchangeReceived(e -> {
          e.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
          e.getMessage().setBody(body);
        });
    return context;
  }
}
