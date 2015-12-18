/*
 * Copyright (C) 2015 Serena Software.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.http.client.HttpClient
import org.apache.http.client.HttpResponseException
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpPost
import org.apache.http.HttpException
import org.apache.http.entity.StringEntity
import org.apache.http.HttpResponse
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.DefaultHttpClient

import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONArray

import org.slf4j.Logger

import sun.misc.BASE64Encoder

import groovy.transform.Field

import org.artifactory.build.promotion.PromotionConfig
import org.artifactory.build.staging.ModuleVersion
import org.artifactory.build.staging.VcsConfig
import org.artifactory.exception.CancelException
import org.artifactory.repo.RepoPathFactory
import org.artifactory.request.Request
import org.artifactory.util.StringInputStream
import org.artifactory.resource.ResourceStreamHandle
import org.artifactory.fs.FileLayoutInfo
import org.artifactory.fs.ItemInfo
import org.artifactory.repo.RepoPath
import org.artifactory.mime.MavenNaming

import static SDAUploadStatuses.*
import static com.google.common.collect.Multimaps.forMap

/**
 * Artifactory User Plugin to publish/upload new artifacts into Serena Deployment Automation.
 *
 * @author Kevin Lee
 */

//
//  Enumeration class to manage SDA Upload status and result properties
//

enum SDAUploadStatuses {
    NEW, // State of the artifact as it is created in Artifactory
    PENDING, // State of all new artifacts just before being published to SDA
    UPLOADED, // State of artifacts already uploaded and upload command returned correctly
    FAILED_UPLOAD, // State of artifacts where upload command failed
	NOT_MAPPED, // State of artifacts if not mapped to SDA component
    UNSUPPORTED, // State of artefact when the type is not supported

    static final SDA_STATUS_PROP_NAME = 'sda.status'
    static final SDA_RESULT_PROP_NAME = 'sda.url'
}

//
// Main entry point to load configuration
//

File configFile = new File(ctx.artifactoryHome.etcDir, "plugins/sda.config")
if (!configFile.isFile()) handleError 400, "No config file was found at ${configFile.absolutePath}"
def sdaConfig = new ConfigSlurper().parse(configFile.toURL())
def sdaDefaults = sdaConfig.defaults.flatten()
def sdaMapping = sdaConfig.mapping.flatten()
String sdaServerURL = sdaConfig.defaults.serverURL
if (!sdaServerURL.endsWith("/")) {
    sdaServerURL = sdaServerURL + "/";
}
String sdaUsername = sdaConfig.defaults.username
String sdaPassword = sdaConfig.defaults.password

log.info "Serena Deployment Automation Publisher"

//def p = 'microsoft.web.infrastructure'
//def flatSdaConfig = sdaConfig.flatten()
//String test1 = sdaMapping.get(p + '.component')
//String test2 = sdaConfig.mapping.microsoft.web.infrastructure.component
//log.info "test1 = ${test1}; test2 = ${test2}"

log.debug "SDA server = " + sdaServerURL
log.debug "Default SDA username = >" + sdaUsername + "<"
//log.info "password = >" + sdaPassword + "<"
//log.info "token = " + sdaAuthToken

//
// Artifactory user plugin extension points
//

jobs {
    // activate SDA Upload workflow every 5 minutes on all new (or other state) items
    activateWorkflow(interval: 20000, delay: 2000) {
        def filter = [:]
        filter.put(SDA_STATUS_PROP_NAME, NEW.name())
        List<RepoPath> paths = searches.itemsByProperties(forMap(filter))
        paths.each { RepoPath newArtifact ->
            log.info "Found artifact ${newArtifact.getName()} that needs to be uploaded"
            setSDAStatus(newArtifact, PENDING)
            // Execute command
            try {
                def result = SDAUpload(newArtifact, sdaDefaults, sdaMapping)
				if (result == "NOT MAPPED") {
					// artifact not mapping to SDA component, ignore it
					setSDAResult(newArtifact, NOT_MAPPED, null)
				} else if (result == "PENDING") {
					// no version data found yet, wait and retry
                } else if (result == "UNSUPPORTED") {
                    // artifact type is not yet supported
                    setSDAResult(newArtifact, UNSUPPORTED, "artifact type unsupported")
				} else {
					// uploaded successfully
					setSDAResult(newArtifact, UPLOADED, result)
				}
            } catch (Exception e) {
                log.debug("exception caught", e)
                setSDAResult(newArtifact, FAILED_UPLOAD, e.getMessage())
            }
        }
    }
}

storage {
    afterCreate { item ->
		log.debug "Created new artefact $item.repoPath"
		log.debug "repoKey = $item.repoPath.repoKey"		// store in version property repoKey
		log.debug "repoPath path = $item.repoPath.path"		// store in version property repoPath, replacing ":" with /
		log.debug "repoPath name = $item.repoPath.name" 	// file to upload

		def conf = repositories.getRepositoryConfiguration(item.repoPath.repoKey)
		log.debug "repository type = " + conf.getType() // i.e. local/remote
		log.debug "repository layout = " + conf.getRepoLayoutRef() // i.e. maven-2-default

		RepoPath repoPath = item.repoPath
		if (repoPath.isFile()) {
			try {
				log.info "Marking artifact $item.repoPath as a candidate to upload to SDA"
				setSDAStatus(repoPath, NEW)
			} catch (Exception e) {
				log.error("Could not set SDA Upload property on $item", e)
			}
		}
    }
}

//
//  user plugin supporting functions
//

String SDAUpload(RepoPath repoPath, def sdaDefaults, def sdaMapping) {
	String defaultServerURL = sdaDefaults.get('serverURL')
	if (!defaultServerURL.endsWith("/")) {
		defaultServerURL = defaultServerURL + "/";
	}
	String defaultUsername = sdaDefaults.get('username')
	String defaultPassword = sdaDefaults.get('password')
	String authToken = makeAuthToken(defaultUsername, defaultPassword)
    def conf = repositories.getRepositoryConfiguration(repoPath.repoKey)
    //log.debug "repository type = " + conf.getType() // i.e. local/remote
    //log.debug "repository layout = " + conf.getRepoLayoutRef() // i.e. maven-2-default
    if (conf.isEnableNuGetSupport()) {
        log.debug "Artifact is a NuGet package..."
        org.artifactory.md.Properties properties = repositories.getProperties(repoPath)
        String nugetId = repositories.getProperty(repoPath, 'nuget.id')
        String nugetVer = repositories.getProperty(repoPath, 'nuget.version')
        if (nugetId) {
            log.info "Found version ${nugetVer} of package ${nugetId}"
			// is this file in the mapping file?
			String mappedComponent = sdaMapping.get(nugetId + '.component')
			String mappedUsername  = sdaMapping.get(nugetId + '.username')
			String mappedPassword  = sdaMapping.get(nugetId + '.password')
			if (mappedComponent) {
            	log.debug "Found mapping to SDA component ${mappedComponent}"
                if (mappedUsername) {
                    authToken = makeAuthToken(mappedUsername, mappedPassword)
                } 		
				// TODO: Upload Nuget package and return SDA version url
				// try if 404 not found...
                String compVerUrl = createComponentVersion(mappedComponent, nugetVer, defaultServerURL, authToken)
				return compVerUrl
			} else {
				log.info "No mapping found for this component, it will not be uploaded"
				// TODO: set status to not required
				return "NOT MAPPED"
			}	
        } else {
            log.info "NuGet metadata not found, waiting..."
			return "PENDING"
        }
    } else if (conf.isEnableGemsSupport()) {
        log.debug "Artifact is a Gem module...this type is not yet supported..."
        return "UNSUPPORTED"
    } else {
        log.debug "Artifact is a maven module..."
        def layoutInfo = repositories.getLayoutInfo(repoPath)
        String mvnGroupId = layoutInfo.getOrganization()
        String mvnArtifactId = layoutInfo.getModule()
        String mvnVerId = layoutInfo.getBaseRevision()
        if (mvnVerId) {
            log.info "Found version ${mvnVerId} of module ${mvnArtifactId}"
            // is this file in the mapping file?
            def sdaComponent = sdaMapping.get(mvnGroupId + "." + mvnArtifactId + '.component')
            def sdaUsername = sdaMapping.get(mvnGroupId + "." + mvnArtifactId + '.username')
            def sdaPassword = sdaMapping.get(mvnGroupId + "." + mvnArtifactId + '.password')
            if (sdaComponent) {
                log.debug "Found mapping to SDA component ${sdaComponent} as user ${sdaUsername}"
                // TODO: Upload Maven module and return SDA version url
                return ">SDA URL<"
            } else {
                log.info "No mapping found for this component, it will not be uploaded"
                // TODO: set status to not required
                return "NOT MAPPED"
            }
        } else {
            log.info "Maven metadata not found, waiting..."
            // TODO, Set to not found
            return "PENDING"
        }
        /*
        FileLayoutInfo currentLayout = repositories.getLayoutInfo(repoPath)
        ['organization', 'module', 'baseRevision', 'folderIntegrationRevision', 'fileIntegrationRevision', 'classifier', 'ext', 'type'].each { String propName ->
            log.info propName + " = " + currentLayout."${propName}"
        }
        //log.info repoPath.getName()
        //log.info repoPath.getRepoKey()
        //log.info repoPath.getPath()
        //log.info repoPath.getParent().getPath()
        */
    }
}

private void setSDAStatus(RepoPath repoPath, SDAUploadStatuses status) {
    log.debug "Setting ${SDA_STATUS_PROP_NAME}=${status} on ${repoPath.getId()}"
    repositories.setProperty(repoPath, SDA_STATUS_PROP_NAME, status.name())
}

private void setSDAResult(RepoPath repoPath, SDAUploadStatuses status, String result) {
    setSDAStatus(repoPath, status)
	if (result) {
		log.debug "Setting ${SDA_RESULT_PROP_NAME}=${result} on ${repoPath.getId()}"
		repositories.setProperty(repoPath, SDA_RESULT_PROP_NAME, result)
	}	
}

/*def isRemote(String repoKey) {
    if (repoKey.endsWith('-cache')) repoKey = repoKey.substring(0, repoKey.length() - 6)
    return repositories.getRemoteRepositories().contains(repoKey)
}*/

//
//  SDA supporting functions
//

private String createComponentVersion(String componentName, String versionName, String sdaServerUrl, String authToken) {
	HttpPost post = new HttpPost("${sdaServerUrl}cli/version/createVersion?component=${componentName}&name=${versionName}")
	log.info "creating new version ${versionName} on component ${componentName}"
	def postResult = executeHttpRequest(authToken, post, 200, null);
    // TODO: extract URL from return and return it
}

private String makeAuthToken(String username, String password) {
	String creds = username + ':' + password
	return "Basic " + creds.bytes.encodeBase64().toString()
}

private HttpResponse executeHttpRequest(String authToken, Object request, int expectedStatus, JSONObject body) {
	// Make sure the required parameters are there
	if ((request == null) || (expectedStatus == null)) exitFailure("An error occurred executing the request.");

	log.debug "Sending request: ${request}"
	if (body != null) log.debug "Body contents: ${body}";

	HttpClient client = new DefaultHttpClient();
	request.setHeader("DirectSsoInteraction", "true");
	request.setHeader("Authorization", authToken);
	if (body) {
		StringEntity input = new StringEntity(body.toString());
		input.setContentType("application/json");
		request.setEntity(input);
	}

	HttpResponse response;
	try {
		response = client.execute(request);
	} catch (HttpException e) {
		exitFailure("There was an error executing the request.");
	}

	int responseCode = response.getStatusLine().getStatusCode();
	if ((responseCode != expectedStatus) && responseCode != 404) {
		httpFailure(response);
	}

	log.debug "Received the response: " + response.getStatusLine();

	return response;
}

/**
 * Write an error message to console and exit on a fail status.
 * @param message The error message to write to the console.
 */
def exitFailure(String message) {
	log.error "${message}";
}

/**
 * Write a HTTP error message to console and exit on a fail status.
 * @param message The error message to write to the console.
 */
def httpFailure(HttpResponse response) {
	log.error "Request failed : " + response.getStatusLine();
	String responseString = new BasicResponseHandler().handleResponse(response);
	log.error "${responseString}";
}