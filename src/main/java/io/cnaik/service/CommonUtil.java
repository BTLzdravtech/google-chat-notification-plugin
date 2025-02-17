package io.cnaik.service;

import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.cnaik.GoogleChatNotification;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class CommonUtil {

    private final GoogleChatNotification googleChatNotification;
    private final TaskListener taskListener;
    private final FilePath ws;
    private final Run build;
    private final LogUtil logUtil;
    private final ResponseMessageUtil responseMessageUtil;

    private static final long TIME_OUT = 15 * 1000;

    public CommonUtil(GoogleChatNotification googleChatNotification) {
        this.googleChatNotification = googleChatNotification;
        this.taskListener = googleChatNotification.getTaskListener();
        this.ws = googleChatNotification.getWs();
        this.build = googleChatNotification.getBuild();
        this.logUtil = googleChatNotification.getLogUtil();
        this.responseMessageUtil = googleChatNotification.getResponseMessageUtil();
    }

    public void send() {

        boolean sendNotificationFlag = checkPipelineFlag();

        if (logUtil.printLogEnabled()) {
            logUtil.printLog("Send Google Chat Notification condition is : " + sendNotificationFlag);
        }

        if (!sendNotificationFlag) {
            return;
        }

        String json;

        json = "{ \"text\": \"" + responseMessageUtil.createTextMessage() + "\"}";

        if (logUtil.printLogEnabled()) {
            logUtil.printLog("Final formatted text: " + json);
        }

        notifyForEachUrl(json);
    }

    private void notifyForEachUrl(String json) {

        String[] urlDetails = googleChatNotification.getUrl().split(",");
        boolean response;
        String[] url;

        for (String urlDetail : urlDetails) {

            response = call(urlDetail, json);

            if (!response && StringUtils.isNotEmpty(urlDetail)
                    && urlDetail.trim().startsWith("id:")) {

                url = urlDetail.trim().split("id:");

                CredentialUtil credentialUtil = new CredentialUtil();
                StringCredentials stringCredentials = credentialUtil.lookupCredentials(url[1]);

                if (stringCredentials != null) {
                    stringCredentials.getSecret();
                    response = call(stringCredentials.getSecret().getPlainText(), json);
                }
            }

            if (!response) {
                logUtil.printLog("Invalid Google Chat Notification URL found: " + urlDetail);
            }
        }
    }

    private boolean checkWhetherToSend() {

        boolean result = false;

        if (build == null || build.getResult() == null || googleChatNotification == null) {
            return result;
        }

        var prevRun = build.getPreviousBuild();
        Result previousResult = (prevRun != null) ? prevRun.getResult() : Result.SUCCESS;

        if (googleChatNotification.isNotifyAborted()
                && Result.ABORTED == build.getResult()) {

            result = true;

        } else if (googleChatNotification.isNotifyFailure()
                && Result.FAILURE == build.getResult()) {

            result = true;

        } else if (googleChatNotification.isNotifyNotBuilt()
                && Result.NOT_BUILT == build.getResult()) {

            result = true;

        } else if (googleChatNotification.isNotifySuccess()
                && Result.SUCCESS == build.getResult()) {

            result = true;

        } else if (googleChatNotification.isNotifyUnstable()
                && Result.UNSTABLE == build.getResult()) {

            result = true;

        } else if (googleChatNotification.isNotifyBackToNormal() && Result.SUCCESS == build.getResult()
                && (Result.ABORTED == previousResult
                || Result.FAILURE == previousResult
                || Result.UNSTABLE == previousResult
                || Result.NOT_BUILT == previousResult)) {

            result = true;

        }

        return result;
    }

    private boolean checkPipelineFlag() {

        if (googleChatNotification != null &&
                !googleChatNotification.isNotifyAborted() &&
                !googleChatNotification.isNotifyBackToNormal() &&
                !googleChatNotification.isNotifyFailure() &&
                !googleChatNotification.isNotifyNotBuilt() &&
                !googleChatNotification.isNotifySuccess() &&
                !googleChatNotification.isNotifyUnstable()) {
            return true;
        }
        return checkWhetherToSend();
    }

    private boolean checkIfValidURL(String url) {
        return (StringUtils.isNotEmpty(url)
                && (url.trim().contains("https") || url.trim().contains("http"))
                && url.trim().contains("?"));
    }

    private boolean call(String urlDetail, String json) {

        if (checkIfValidURL(urlDetail)) {
            try {

                if (googleChatNotification.isSameThreadNotification()) {
                    String jobName = TokenMacro.expandAll(build, ws, taskListener, "${JOB_NAME}", false, null);
                    urlDetail = urlDetail + "&threadKey=" + URLEncoder.encode(jobName, StandardCharsets.UTF_8);
                }

                var client = HttpClient.newHttpClient();

                var request = HttpRequest.newBuilder()
                        .uri(URI.create(urlDetail))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(TIME_OUT))
                        .build();

                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    var body = response.body();

                    if (logUtil.printLogEnabled()) {
                        logUtil.printLog("Google Chat post may have failed. Response: " + body
                                + " , Response Code: " + response.statusCode());
                    }
                }

            } catch (Exception e) {
                logUtil.printLog("Exception while posting Google Chat message: " + e.getMessage());
            }
            return true;
        }
        return false;
    }
}