/*
 *
 *  *
 *  * SecureCodeBox (SCB)
 *  * Copyright 2015-2018 iteratec GmbH
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * 	http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package io.securecodebox.zap.service.zap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.otto.edison.status.domain.Status;
import de.otto.edison.status.domain.StatusDetail;
import de.otto.edison.status.indicator.StatusDetailIndicator;
import de.sstoehr.harreader.HarReader;
import de.sstoehr.harreader.HarReaderException;
import de.sstoehr.harreader.model.Har;
import de.sstoehr.harreader.model.HarRequest;
import io.securecodebox.zap.configuration.ZapConfiguration;
import io.securecodebox.zap.service.engine.model.Finding;
import io.securecodebox.zap.service.engine.model.Reference;
import io.securecodebox.zap.service.engine.model.Target;
import io.securecodebox.zap.service.engine.model.zap.ZapAuthenticationMethod;
import io.securecodebox.zap.service.engine.model.zap.ZapReplacerRule;
import io.securecodebox.zap.service.engine.model.zap.ZapSitemapEntry;

import java.io.*;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;

import io.securecodebox.zap.service.engine.model.zap.ZapTargetAttributes;
import io.securecodebox.zap.service.zap.model.ScriptEngines;
import io.securecodebox.zap.service.zap.model.ScriptTypes;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.parosproxy.paros.core.scanner.Plugin.AttackStrength;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.togglz.core.util.Strings;
import org.zaproxy.clientapi.core.Alert;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Context;


import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;


/**
 * Encapsulates all relevant OWASP ZAP methods.
 */
@Service
@Slf4j
@ToString
public class ZapService implements StatusDetailIndicator {

    private static final String SESSION_NAME = "secureCodeBoxSession";
    private static final String CONTEXT_NAME = "secureCodeBoxContext";
    private static final String AUTH_USER = "Testuser";
    private static final String AUTH_FORM_BASED = "formBasedAuthentication";
    private static final String AUTH_SCRIPT_BASED = "scriptBasedAuthentication";

    /**
     * This map maps each constant of {@link AttackStrength} to the name of a policy which is designed
     * to have the corresponding attack strength.
     */
    private static final Map<AttackStrength, String> ATTACK_STRENGTH_POLICY_NAME_MAP =
            initAttackStrengthPolicyNameMap();

    private static Integer defaultDelayInMs;
    private static Integer defaultThreadsPerHost;

    /**
     * Don't use this method! Use {@link #ATTACK_STRENGTH_POLICY_NAME_MAP} instead!
     *
     * @return the initialized value for {@link #ATTACK_STRENGTH_POLICY_NAME_MAP}
     */
    private static Map<AttackStrength, String> initAttackStrengthPolicyNameMap() {
        Map<AttackStrength, String> attackStrengthPolicyNameMap = new HashMap<>();
        attackStrengthPolicyNameMap.put(AttackStrength.INSANE, "InsaneAttackStrengthPolicy");
        attackStrengthPolicyNameMap.put(AttackStrength.HIGH, "HighAttackStrengthPolicy");
        attackStrengthPolicyNameMap.put(AttackStrength.MEDIUM, "MediumAttackStrengthPolicy");
        attackStrengthPolicyNameMap.put(AttackStrength.LOW, "LowAttackStrengthPolicy");
        attackStrengthPolicyNameMap.put(AttackStrength.DEFAULT, "DefaultAttackStrengthPolicy");

        return  attackStrengthPolicyNameMap;
    }

    private final ZapConfiguration config;
    private ClientApi api;
    private ReplacerPluginConfigurator replacerPluginConfigurator;

    @Autowired
    public ZapService(ZapConfiguration config) {
        this.config = config;
    }

    private static String getSingleResult(ApiResponse response) {
        if (response instanceof ApiResponseElement) {
            return ((ApiResponseElement) response).getValue();
        }
        return "";
    }

    @PostConstruct
    public void init() throws ClientApiException {
        api = new ClientApi(config.getZapHost(), config.getZapPort());
        replacerPluginConfigurator = new ReplacerPluginConfigurator(api);

        if (defaultDelayInMs == null && defaultThreadsPerHost == null) {
            defaultDelayInMs = Integer.valueOf(api.ascan.optionDelayInMs().toString());
            defaultThreadsPerHost = Integer.valueOf(api.ascan.optionThreadPerHost().toString());
            log.debug("Set default rate limits to defaultDelayInMs:{}, defaultThreadsPerHost:{}", defaultDelayInMs, defaultThreadsPerHost);
        }
    }

    /**
     * Create a new session and context based on the given URL.
     *
     * @param targetUrl Target URL to create a new session and context for
     * @return Created context ID
     */
    public String createContext(String targetUrl, List<String> contextIncludeRegex, List<String> contextExcludeRegex) throws ClientApiException {
        log.info("Starting to create a new ZAP session '{}' and context '{}'.", SESSION_NAME, CONTEXT_NAME);

        contextIncludeRegex.add("\\Q" + targetUrl + "\\E.*");

        api.core.newSession(SESSION_NAME, "true");

        Context context = new Context(api);
        String contextId = getSingleResult(context.newContext(CONTEXT_NAME));
        for (String regex : contextIncludeRegex) {
            if (regex != null && !regex.isEmpty()) {
                context.includeInContext(CONTEXT_NAME, regex);
            }
        }

        for (String regex : contextExcludeRegex) {
            context.excludeFromContext(CONTEXT_NAME, regex);
        }

        api.httpSessions.createEmptySession(targetUrl, SESSION_NAME);
        api.httpSessions.setActiveSession(targetUrl, SESSION_NAME);

        return contextId;
    }

    public void clearSession() throws ClientApiException {
        api.spider.removeAllScans();
        api.ascan.removeAllScans();
        replacerPluginConfigurator.resetReplacerRules();

        api.core.newSession(SESSION_NAME, "true");
    }

    private void loadScript(String name, ScriptTypes type, ScriptEngines engine, String fileName, String description) throws ClientApiException {
        log.info("Loading authentication script '{}'", fileName);
        try {
            api.script.remove(name);
            log.info("Deleted existing authentication script {}", name);
        } catch (ClientApiException err) {
            // Probably a NotFound error.
            log.info("Tried to delete existing script '{}' but didn't exist.", name);
        }

        api.script.load(
                name,
                type.toString(),
                engine.toString(),
                fileName,
                description
        );
        log.info("Script {} loaded successfully.", name);
    }

    /**
     * Configure the authentication based on the given user name and password field.
     *
     * @return New user ID
     */
    public String configureAuthentication(String contextId, Target target) throws ClientApiException, UnsupportedEncodingException {
        ZapTargetAttributes attributes = target.getAttributes();

        String loginUrl = attributes.getLoginSite();
        String usernameFieldId = attributes.getLoginUsernameFieldId();
        String passwordFieldId = attributes.getPwFieldId();
        String username = attributes.getLoginUser();
        String password = attributes.getLoginPw();
        String loggedInIndicator = attributes.getLoggedInIndicator();
        String loggedOutIndicator = attributes.getLoggedOutIndicator();
        String csrfToken = attributes.getCsrfTokenId();

        log.info("Configuring ZAP based authentication for user '{}' and loginUrl '{}'", username, loginUrl);

        ZapAuthenticationMethod authenticationMethod = attributes.getAuthenticationMethod();

        switch (authenticationMethod) {
            case FormBased:
                String formArgs = "loginUrl=" + URLEncoder.encode(loginUrl, "UTF-8")
                        + "&loginRequestData="
                        + URLEncoder.encode(usernameFieldId + "={%username%}&" + passwordFieldId + "={%password%}", "UTF-8");
                api.authentication.setAuthenticationMethod(
                        contextId,
                        AUTH_FORM_BASED,
                        formArgs
                );
                break;
            case CsrfLoginScript:
                api.acsrf.addOptionToken(csrfToken);
                api.script.load("csrfAuthScript", "authentication", "Oracle Nashorn",
                        "csrfAuthScript.js", "csrfloginscript");

                String csrfArgs = "scriptName=csrfAuthScript"
                        + "&LoginURL=" + loginUrl
                        + "&CSRFField=" + csrfToken
                        + "&POSTData=" + URLEncoder.encode(usernameFieldId + "={%username%}&" + passwordFieldId + "={%password%}&" + csrfToken + "={%user_token%}", "UTF-8");
                api.authentication.setAuthenticationMethod(
                        contextId,
                        AUTH_SCRIPT_BASED,
                        csrfArgs
                );
                break;
            case ScriptBased:
                log.info("Loading custom authentication script");

                loadScript(
                        "scb-custom-authentication",
                        ScriptTypes.Authentication,
                        ScriptEngines.OracleNashorn,
                        attributes.getAuthenticationScriptPath(),
                        "Custom authentication script for secureCodeBox orchestrated ZAP scan"
                );

                String scriptArgs = "scriptName=scb-custom-authentication";

                if(Strings.isNotEmpty(attributes.getAuthenticationScriptParameters())) {
                    scriptArgs += "&" + attributes.getAuthenticationScriptParameters();
                }

                log.info("Setting script as authentication method for context with args: '{}'", scriptArgs);

                api.authentication.setAuthenticationMethod(
                        contextId,
                        AUTH_SCRIPT_BASED,
                        scriptArgs
                );
                log.info("Script set as authentication method");
                break;
        }

        if (loggedInIndicator != null && !loggedInIndicator.isEmpty()) {
            api.authentication.setLoggedInIndicator(contextId, "\\Q" + loggedInIndicator + "\\E");
        }
        if (loggedOutIndicator != null && !loggedOutIndicator.isEmpty()) {
            api.authentication.setLoggedOutIndicator(contextId, "\\Q" + loggedOutIndicator + "\\E");
        }

        String userId = getSingleResult(api.users.newUser(contextId, AUTH_USER));
        api.users.setAuthenticationCredentials(contextId, userId, "username=" + username + "&password=" + password);
        api.users.setUserEnabled(contextId, userId, "true");
        api.forcedUser.setForcedUser(contextId, userId);
        api.forcedUser.setForcedUserModeEnabled(true);

        return userId;
    }

    public void configureSessionManagement(String contextId, Target target) throws ClientApiException {
        switch (target.getAttributes().getSessionManagementMethod()){
            case CookieBased:
                log.info("Setting SessionManagement to CookieBased");
                api.sessionManagement.setSessionManagementMethod(contextId, "cookieBasedSessionManagement", null);
                break;
            case ScriptBased:
                log.info("Setting SessionManagement to ScriptBased");

                loadScript(
                        "scb-custom-session-management",
                        ScriptTypes.SessionManagment,
                        ScriptEngines.OracleNashorn,
                        target.getAttributes().getSessionManagementScriptPath(),
                        "Custom session management script for secureCodeBox orchestrated ZAP scan"
                );

                String scriptArgs = "scriptName=scb-custom-session-management";

                if(Strings.isNotEmpty(target.getAttributes().getSessionManagementScriptParameters())) {
                    scriptArgs += "&" + target.getAttributes().getSessionManagementScriptParameters();
                }

                log.info("Setting script as session management method for context with args: '{}'", scriptArgs);

                api.sessionManagement.setSessionManagementMethod(
                        contextId,
                        "scriptBasedSessionManagement",
                        scriptArgs
                );
                log.info("Script set as session management method");
                break;
        }
    }

    /**
     * Recalls a request for putting it in the ZAP cache
     * This sends out all spider requests again proxied by the zap proxy.
     *
     * @param target the Target containing a sitemap with all requests to recall
     */
    public void recallTarget(Target target, ZapReplacerRule[] zapReplacerRules) throws ClientApiException {
        List<ZapSitemapEntry> sitemap = target.getAttributes().getSitemap();
        if (sitemap == null) {
            log.warn("Tried to recall a empty sitemap to Zap. The scan will fail as it will not have any targets to scan!");
            return;
        }

        replacerPluginConfigurator.configureZapWithReplacerRules(zapReplacerRules);

        log.info("Recalling {} requests to zap.", sitemap.size());
        ObjectMapper objMapper = new ObjectMapper();
        for (ZapSitemapEntry entry : sitemap) {
            try {
                String requestHar = objMapper.writeValueAsString(entry);
                byte[] response = api.core.sendHarRequest(requestHar, "");
                String msg = new String(response);
                log.debug("Recalled target to ZAP with following response {}", msg);
            } catch (JsonProcessingException e) {
                log.error("Couldn't convert Har Request Object to JSON string!", e);
            } catch (ClientApiException e) {
                log.error("Couldn't upload Har Request to ZAP!", e);
            }
        }
    }

    /**
     * @param userId User ID to start the spider scan as, "-1" to ignore
     * @return New spider scan ID
     */
    public String startSpiderAsUser(String targetUrl, String apiSpecUrl, int maxDepth, String contextId, String userId, ZapReplacerRule[] replacerRules) throws ClientApiException {
        log.info("Starting spider for targetUrl '{}' and with apiSpecUrl '{}' and maxDepth '{}'", targetUrl, apiSpecUrl, maxDepth);

        replacerPluginConfigurator.configureZapWithReplacerRules(replacerRules);

        if (apiSpecUrl != null && !apiSpecUrl.isEmpty()) {
            api.openapi.importUrl(apiSpecUrl, null);
        }
        api.spider.setOptionMaxDepth(maxDepth);
        api.spider.setOptionParseComments(true);
        api.spider.setOptionParseGit(true);
        api.spider.setOptionParseSVNEntries(true);
        api.spider.setOptionParseSitemapXml(true);
        api.spider.setOptionParseRobotsTxt(true);

        ApiResponse response = ("-1".equals(userId))
                ? api.spider.scan(targetUrl, "-1", null, CONTEXT_NAME, null)
                : api.spider.scanAsUser(contextId, userId, targetUrl, "-1", null, null);

        return getSingleResult(response);
    }

    /**
     * @param userId     User ID to start the scan as, "-1" to ignore
     * @param attributes ZapScan Attributes
     * @return New scanner scan ID
     */
    public String startScannerAsUser(String targetUrl, String contextId, String userId, ZapTargetAttributes attributes) throws ClientApiException {
        log.info("Starting scanner for targetUrl '{}' and userId {}.", targetUrl, userId);

        Integer delayInMs = attributes.getScannerDelayInMs();
        Integer threadsPerHost = attributes.getThreadsPerHost();
        ZapReplacerRule[] replacerRules = attributes.getZapReplacerRules();
        String attackStrength = attributes.getScanAttackStrength();

        api.ascan.enableAllScanners(null);
        api.ascan.setOptionHandleAntiCSRFTokens(true);
        setRateLimits(delayInMs, threadsPerHost);

        replacerPluginConfigurator.configureZapWithReplacerRules(replacerRules);
        String policyName = choosePolicyForAttackStrength(attackStrength);

        ApiResponse response = ("-1".equals(userId))
                ? api.ascan.scan(targetUrl, "true", "false", policyName, null, null)
                : api.ascan.scanAsUser(targetUrl, contextId, userId, "true", policyName, null, null);

        return getSingleResult(response);
    }

    /**
     * If attackStrengthName is set to null, this method will return null.
     * Otherwise it returns the name of a policy whose attack strength has a given name.
     * Especially this method ensures that the policy with the returned name exists and has the
     * attack strength of the given name.
     *
     * The value of attackStrengthName must be null or the name of one of the constants of {@link AttackStrength}.
     *
     * @param attackStrengthName name of the attack strength of the policy with the returned name.
     *                           Allowed values are null and the constant names of {@link AttackStrength}
     * @return null or the name of a policy whose attack strength has a given name.
     * @throws ClientApiException The policy of the returned name is created or updated with the ZAP API, which might
     * lead to this exception
     */
    private String choosePolicyForAttackStrength(String attackStrengthName) throws ClientApiException {
        if (attackStrengthName != null) {
            AttackStrength attackStrength = AttackStrength.valueOf(attackStrengthName);
            ensurePolicyForAttackStrengthIsAvailable(attackStrength);
            return ATTACK_STRENGTH_POLICY_NAME_MAP.get(attackStrength);
        }

        return null;
    }

    /**
     * Ensures that the policy for the given attackStrength is available with the correct attack strength.
     * The map {@link #ATTACK_STRENGTH_POLICY_NAME_MAP} maps each {@link AttackStrength} to the name of a policy, which
     * is supposed to have the corresponding attack strength.
     * Consider the policy to which {@link #ATTACK_STRENGTH_POLICY_NAME_MAP} maps the given attackStrength.
     * This method ensures that this policy exists and has the given attack strength.
     *
     * @param attackStrength Ensure that the policy for this attack strength is available
     * @throws ClientApiException This method uses the Zap Api, which might lead to this exception
     */
    private void ensurePolicyForAttackStrengthIsAvailable(AttackStrength attackStrength) throws ClientApiException {
        String policyName = ATTACK_STRENGTH_POLICY_NAME_MAP.get(attackStrength);

        try {
            api.ascan.addScanPolicy(policyName, null, attackStrength.toString());
        } catch(ClientApiException clientApiException) {
            String errorMessage = clientApiException.getMessage();
            if ("Already Exists".equals(errorMessage)) {
                api.ascan.updateScanPolicy(policyName, null, attackStrength.toString());
            } else {
                throw clientApiException;
            }
        }
    }

    private void setRateLimits(Integer delayInMs, Integer threadsPerHost) throws ClientApiException {
        log.debug("Set rate limits for scan");
        if (delayInMs != null) {
            log.debug("Set DelayInMs:{}", delayInMs);
            api.ascan.setOptionDelayInMs(delayInMs);
        } else {
            log.debug("Set DelayInMs to default ({})", defaultDelayInMs);
            api.ascan.setOptionDelayInMs(defaultDelayInMs);
        }

        if (threadsPerHost != null) {
            log.debug("Set threadsPerHost:{}", threadsPerHost);
            api.ascan.setOptionThreadPerHost(threadsPerHost);
        } else {
            log.debug("Set threadsPerHost to default ({})", defaultThreadsPerHost);
            api.ascan.setOptionThreadPerHost(defaultThreadsPerHost);
        }
    }

    /**
     * Wait until the spider scan finished, then return its result.
     *
     * @return JSON string
     */
    public List<Finding> retrieveSpiderResult(String scanId) throws ClientApiException {
        try {
            int progress = 0;
            while (progress < 100) {
                progress = Integer.parseInt(getSingleResult(api.spider.status(scanId)));
                log.info("Spider (ID: {}) progress: {}%", scanId, progress);
                Thread.sleep(1000);
            }
            log.info("Spider (ID: {}) completed.", scanId);
        } catch (InterruptedException e) {
            log.error("Couldn't wait until spider finished!", e);
        }

        ApiResponse response = api.spider.fullResults(scanId);
        List<Finding> findings = new ArrayList<>(1);
        if (response instanceof ApiResponseList) {
            findings = ((ApiResponseList) response).getItems().stream()
                    .map(i -> ((ApiResponseList) i).getItems())
                    .flatMap(Collection::stream)
                    .filter(r -> r instanceof ApiResponseSet)
                    .map(r -> {
                        Finding finding = new Finding();
                        HarRequest harRequest = getHarRequestPortionForRequest(((ApiResponseSet) r).getStringValue("messageId"));
                        finding.setLocation(harRequest.getUrl());
                        finding.getAttributes().put("request", harRequest);
                        return finding;
                    })
                    .collect(Collectors.toList());
        }

        log.info("Found #{} spider URLs for the scanId:{}", findings.size(), scanId);

        return findings;
    }

    /**
     * Wait until the scanner scan finished, then return its result.
     *
     * @return JSON string
     */
    public List<Finding> retrieveScannerResult(String scanId, String targetUrl) throws ClientApiException {
        try {
            int progress = 0;
            while (progress < 100) {
                progress = Integer.parseInt(getSingleResult(api.ascan.status(scanId)));
                log.info("Scanner (ID: {}) progress: {}%", scanId, progress);
                Thread.sleep(5000);
            }
            log.info("Scanner (ID: {}) completed.", scanId);
        } catch (InterruptedException e) {
            log.error("Couldn't wait until scanner finished!", e);
        }

        List<Alert> result = api.getAlerts(targetUrl, -1, -1);

        List<Finding> findings = result.stream().map(alert -> {
            Finding finding = new Finding();
            finding.setLocation(alert.getUrl());
            finding.setName(alert.getName());
            finding.setSeverity(alert.getRisk().name());
            finding.setDescription(alert.getDescription());
            finding.setHint(alert.getSolution());
            finding.setCategory(alert.getName());

            finding.getAttributes().put("HAR", getHarForRequest(alert.getMessageId()));
            finding.getAttributes().put("OTHER", alert.getOther());
            finding.getAttributes().put("ATTACK", alert.getAttack());
            finding.getAttributes().put("CONFIDENCE", alert.getConfidence().name());
            finding.getAttributes().put("EVIDENCE", alert.getEvidence());
            finding.getAttributes().put("WASC_ID", alert.getWascId());
            finding.getAttributes().put("PLUGIN_ID", alert.getPluginId());
            finding.getAttributes().put("OTHER_REFERENCES", alert.getReference().split("\n"));

            Reference reference = new Reference();
            reference.setId("CVE-" + alert.getCweId());
            reference.setSource("https://cwe.mitre.org/data/definitions/" + alert.getCweId() + ".html");
            finding.setReference(reference);

            return finding;
        }).collect(Collectors.toList());
        log.info("Found #{} alerts for targetUrl: {}", result.size(), targetUrl);

        return findings;
    }

    /**
     * Returns the HAR (HTTP Archive) of a single entry in the ZAP Tree.
     * Currently only the request portion is required and used.
     * <p>
     * This sends a Request to the ZapAPI
     *
     * @param requestId aka MessageId
     * @return HAR of the requestId
     */
    Har getHarForRequest(String requestId) {
        HarReader harReader = new HarReader();

        try {
            String harString = new String(api.core.messageHar(requestId));
            return harReader.readFromString(harString);
        } catch (ClientApiException e) {
            log.warn("Could not fetch Request HAR Object from ZAP.", e.getMessage());
        } catch (HarReaderException e) {
            log.warn("Could not parse Request HAR Object returned from ZAP.", e.getMessage());
        }
        return null;
    }

    HarRequest getHarRequestPortionForRequest(String requestId) {
        Har har = getHarForRequest(requestId);

        if (har.getLog().getEntries().size() == 0) {
            return null;
        }

        return har.getLog().getEntries().get(0).getRequest();
    }

    /**
     * This status checks if the configured ZAP API is reachable and returning an API result.
     */
    @Override
    public List<StatusDetail> statusDetails() {
        try {
            String version = this.getVersion();

            if (version != null && !version.isEmpty()) {
                log.debug("Internal status check: ok");
                return singletonList(StatusDetail.statusDetail("ZAP API", Status.OK, "The ZAP API is up and running", singletonMap("ZAP Version", version)));
            } else {
                return singletonList(StatusDetail.statusDetail("ZAP API", Status.WARNING, "Warning, couldn't find any ZAP version information's. Propably an error occurred!"));
            }
        } catch (ClientApiException e) {
            log.debug("Error: indicating a status problem!", e);
            return singletonList(StatusDetail.statusDetail(getClass().getSimpleName(), Status.ERROR, e.getMessage()));
        }
    }

    public String getVersion() throws ClientApiException {
        return getSingleResult(api.core.version());
    }

    public String getRawReport() throws ClientApiException {
        return new String(api.core.xmlreport());
    }
}
