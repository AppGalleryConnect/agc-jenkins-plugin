package com.huawei.jenkins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import java.io.File;
import java.io.IOException;
import javax.servlet.ServletException;
import jenkins.tasks.SimpleBuildStep;
import org.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class AppGalleryUploadBuilder extends Builder implements SimpleBuildStep {
    
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    OkHttpClient client = new OkHttpClient();
        
    private final String path, secret, clientId, appId, suffix;

    public String getPath() {
        return path;
    }

    public String getSecret() {
        return secret;
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
    public AppGalleryUploadBuilder(String path, String secret, String cliendId, String appId, String suffix) {
        this.path = path;
        this.secret = secret;
        this.clientId = cliendId;
        this.appId = appId;
        this.suffix = suffix;
    }
    
    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        
        String accessToken = getAccessToken();
        
        JSONObject uploadDetails = getUploadDetails(accessToken);
        String uploadUrl = uploadDetails.getString("uploadUrl");
        String uploadAuthCode = uploadDetails.getString("authCode");
        
        String fileURL = uploadFile(uploadUrl, accessToken, uploadAuthCode);
        
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
    
    JSONObject getUploadDetails(String authCode) throws IOException{
        return get("https://connect-api.cloud.huawei.com/api/publish/v2/upload-url?appId="+getAppId()+"&suffix="+getSuffix(), authCode);
    } 
    
    void submitApp(String authHeader) throws IOException {
        post("https://connect-api.cloud.huawei.com/api/publish/v2/app-submit?appId="+getAppId(), "", authHeader);
    }
    
    String getAccessToken() throws IOException{
        JSONObject authJson = new JSONObject();
        authJson.put("client_id", getClientId());
        authJson.put("client_secret", getSecret());
        authJson.put("grant_type", "client_credentials");
        
        String jsonString = authJson.toString();
        JSONObject authResponse = post("https://connect-api.cloud.huawei.com/api/oauth2/v1/token", jsonString, null);
        
        return authResponse.getString("access_token");
    }
    
    void attachFileToApp(String authHeader, String fileURL) throws IOException{
        JSONObject files = new JSONObject();
        files.put("fileName", "app.apk");
        files.put("fileDestUrl", fileURL);

        JSONObject authJson = new JSONObject();
        authJson.put("fileType", "5");
        authJson.put("files", files);
        
        String jsonString = authJson.toString();
        put("https://connect-api.cloud.huawei.com/api/publish/v2/app-file-info?appId="+getAppId(), jsonString, authHeader);
    }
    
    JSONObject post(String url, String json, String authHeader) throws IOException {
        RequestBody body = RequestBody.create(JSON, json);
        Request.Builder requestBuilder = new Request.Builder()
            .url(url)
            .addHeader("client_id", getClientId())
            .post(body);
        if (authHeader != null ){
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
        if (authHeader != null ){
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
        if (authHeader != null ){
            requestBuilder.addHeader("Authorization", "Bearer " + authHeader);
        }
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
          return new JSONObject(response.body().string());
        }
    }
    
    String uploadFile(String url, String authHeader, String fileAuthCode) throws IOException{
        
        File file = new File(getPath());
        MediaType mediaType = MediaType.parse("application/vnd.android.package-archive");
        RequestBody requestFileBody = RequestBody.create(mediaType, file);
        
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", null, requestFileBody)
                .addFormDataPart("authCode", fileAuthCode) 
                .addFormDataPart("fileCount", "1")
                .addFormDataPart("parseType", "1")
                .build();
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("client_id", getClientId())
                .post(body);
        
        if (authHeader != null ){
            requestBuilder.addHeader("Authorization", "Bearer " + authHeader);
        }
        
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
          JSONObject responseJSON = new JSONObject(response.body().string());
          
          return responseJSON.getJSONObject("result").getJSONObject("UploadFileRsp").getJSONArray("fileInfoList").getJSONObject(0).getString("fileDestUlr");
       
        }
    }
    

}
