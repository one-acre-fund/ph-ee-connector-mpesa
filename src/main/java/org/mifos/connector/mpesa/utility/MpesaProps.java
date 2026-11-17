package org.mifos.connector.mpesa.utility;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Configuration
@ConfigurationProperties(prefix = "accounts")
public class MpesaProps {

    private List<MPESA> group;

    public List<MPESA> getGroup() {
        return group;
    }

    public void setGroup(List<MPESA> amsgroup) {
        this.group = amsgroup;
    }


    public static class MPESA{

        private String name;
        private String businessShortCode;
        private String till;
        private String authHost;
        private String clientKey;
        private String clientSecret;
        private String apiHost;
        private String passKey;
        private B2C b2c = new B2C();


        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getBusinessShortCode() {
            return Long.parseLong(businessShortCode);
        }

        public void setBusinessShortCode(String businessShortCode) {
            this.businessShortCode = businessShortCode.trim();
        }

        public Long getTill() {
            return Long.parseLong(till);
        }

        public void setTill(String till) {
            this.till = till.trim();
        }

        public String getAuthHost() {
            return authHost;
        }

        public void setAuthHost(String authHost) {
            this.authHost = authHost;
        }

        public String getClientKey() {
            return clientKey;
        }

        public void setClientKey(String clientKey) {
            this.clientKey = clientKey;
        }

        public String getApiHost() {
            return apiHost;
        }

        public void setApiHost(String apiHost) {
            this.apiHost = apiHost;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getPassKey() {
            return passKey;
        }

        public void setPassKey(String passKey) {
            this.passKey = passKey;
        }

        public B2C getB2c() {
            return b2c;
        }

        public void setB2c(B2C b2c) {
            this.b2c = b2c;
        }

        public String getDefaultClientKey(){
            String key = null;
            if(getName().equals("default")){
               key =  getClientKey();
            }
            return key;
        }
        public String getDefaultClientSecret(){
            String secret = null;
            if(getName().equals("default")){
                secret =  getClientSecret();
            }
            return secret;
        }

        @Override
        public String toString() {
            return "MPESA{" +
                "name='" + name + '\'' +
                ", businessShortCode='" + businessShortCode + '\'' +
                ", till='" + till + '\'' +
                '}';
        }
    }

    /**
     * B2C (payout) settings of an account. The client key and secret fall back to the account's own when the B2C
     * shortcode uses the same Daraja app.
     */
    @Getter
    @Setter
    public static class B2C {
        private boolean enabled;
        private String shortCode;
        private String initiatorName;
        // The initiator password encrypted with the Safaricom certificate and base64 encoded, never the plain password
        private String securityCredential;
        private String clientKey;
        private String clientSecret;
        private String commandId = "BusinessPayment";
        private long minAmount = 10;
        private long maxAmount = 250000;

        @Override
        public String toString() {
            return "B2C{enabled=" + enabled + ", shortCode='" + shortCode + "', initiatorName='" + initiatorName
                    + "', commandId='" + commandId + "'}";
        }
    }
}

