package com.huawei.jenkins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.ClassFilter;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import okhttp3.*;
import org.jenkinsci.Symbol;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;

public class AppGalleryUploadBuilder extends Builder implements SimpleBuildStep {

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    OkHttpClient client = new OkHttpClient();

    private final String path, clientId, appId, suffix;
    private final Secret secret;
    private FilePath workspaceFilePath;

    public String getPath() {
        return path;
    }

    public String getWorkspacePath() {
        return workspaceFilePath.toString();
    }

    public String getSecret() {
        return secret.getPlainText();
    }

    public String getClientId() {
        return clientId;
    }

    public String getAppId() {
        return appId;
    }

    public String getSuffix() {
        return suffix;
    }

    @DataBoundConstructor
    public AppGalleryUploadBuilder(String path, Secret secret, String clientId, String appId, String suffix) {
        this.path = path;
        this.secret = secret;
        this.clientId = clientId;
        this.appId = appId;
        this.suffix = suffix;
        ClassFilter.setDefault(ClassFilter.NONE);
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {

        listener.getLogger().println("Starting Upload");
        workspaceFilePath = workspace.child(getPath());
        String accessToken = getAccessToken();
        listener.getLogger().println("Got Access Token");

        JSONObject uploadDetails = getUploadDetails(accessToken);
        listener.getLogger().println("Got Upload Details");
        String uploadUrl = uploadDetails.getString("uploadUrl");
        String uploadAuthCode = uploadDetails.getString("authCode");

        listener.getLogger().println(MessageFormat.format("UploadUrl: {0}", uploadUrl));
        String fileURL = uploadFile(uploadUrl, accessToken, uploadAuthCode, listener);

        attachFileToApp(accessToken, fileURL);
        submitApp(accessToken);

        listener.getLogger().println("Uploaded");

    }

    @Symbol("greet")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckName(@QueryParameter String value, @QueryParameter boolean useFrench)
                throws IOException, ServletException {

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Upload Application to AppGallery";
        }

    }

    JSONObject getUploadDetails(String authCode) throws IOException {
        return get("https://connect-api.cloud.huawei.com/api/publish/v2/upload-url?appId=" + getAppId() + "&suffix=" + getSuffix(), authCode);
    }

    void submitApp(String authHeader) throws IOException {
        post("https://connect-api.cloud.huawei.com/api/publish/v2/app-submit?appId=" + getAppId(), "", authHeader);
    }

    String getAccessToken() throws IOException {
        JSONObject authJson = new JSONObject();
        authJson.put("client_id", getClientId());
        authJson.put("client_secret", getSecret());
        authJson.put("grant_type", "client_credentials");

        String jsonString = authJson.toString();
        JSONObject authResponse = post("https://connect-api.cloud.huawei.com/api/oauth2/v1/token", jsonString, null);

        return authResponse.getString("access_token");
    }

    void attachFileToApp(String authHeader, String fileURL) throws IOException {
        JSONObject files = new JSONObject();
        files.put("fileName", "app.apk");
        files.put("fileDestUrl", fileURL);

        JSONObject authJson = new JSONObject();
        authJson.put("fileType", "5");
        authJson.put("files", files);

        String jsonString = authJson.toString();
        put("https://connect-api.cloud.huawei.com/api/publish/v2/app-file-info?appId=" + getAppId(), jsonString, authHeader);
    }

    JSONObject post(String url, String json, String authHeader) throws IOException {
        RequestBody body = RequestBody.create(JSON, json);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("client_id", getClientId())
                .post(body);
        if (authHeader != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + authHeader);
        }
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    JSONObject get(String url, String authHeader) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .addHeader("client_id", getClientId())
                .url(url);
        if (authHeader != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + authHeader);
        }
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    JSONObject put(String url, String json, String authHeader) throws IOException {
        RequestBody body = RequestBody.create(JSON, json);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("client_id", getClientId())
                .put(body);
        if (authHeader != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + authHeader);
        }
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    String uploadFile(String url, String authHeader, String fileAuthCode, TaskListener listener) throws IOException {

        File file = new File(getWorkspacePath());
        MediaType mediaType = MediaType.parse("application/vnd.android.package-archive");
        RequestBody requestFileBody = RequestBody.create(mediaType, file);
        if (file.exists())
            listener.getLogger().println("Application File: " + file.getAbsolutePath());

        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "upload.apk", requestFileBody)
                .addFormDataPart("authCode", fileAuthCode)
                .addFormDataPart("fileCount", "1")
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("client_id", getClientId())
                .post(body);

        OkHttpClient client = new OkHttpClient().newBuilder()
                .build();
        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            JSONObject responseJSON = new JSONObject(response.body().string());
            listener.getLogger().println("Got file upload response");

            return responseJSON.getJSONObject("result").getJSONObject("UploadFileRsp").getJSONArray("fileInfoList").getJSONObject(0).getString("fileDestUlr");

        }
    }
}
