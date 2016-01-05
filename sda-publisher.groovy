/*
 * Copyright (C) 2016 Serena Software.
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

import static org.artifactory.util.PathUtils.getExtension

import static SDAUploadStatuses.*
import static com.google.common.collect.Multimaps.forMap

/**
 * Artifactory User Plugin to publish/upload new artifacts into Serena Deployment Automation.
 *
 * @author Kevin Lee (klee@serena.com)
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
	NOT_EXISTS, // State of artifacts if SDA component does not exist
    UNSUPPORTED, // State of artefact when the type is not supported
	UNKNOWN, // Some form of unknown error ...

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
    sdaServerURL = sdaServerURL + "/"
}
String sdaUsername = sdaConfig.defaults.username
String sdaPassword = sdaConfig.defaults.password
String sdaAuthToken = createAuthToken(sdaUsername, sdaPassword)

log.info "Serena Deployment Automation Publisher"
log.debug "Default SDA server = " + sdaServerURL
log.debug "Default SDA username = " + sdaUsername

checkServerExists(sdaServerURL, sdaAuthToken)


//
// Artifactory user plugin extension points
//

jobs {
    // activate SDA Upload workflow every 5 minutes on all NEW items
    activateWorkflow(interval: 20000, delay: 2000) {
        def filter = [:]
        filter.put(SDA_STATUS_PROP_NAME, NEW.name())
        List<RepoPath> paths = searches.itemsByProperties(forMap(filter))
        paths.each { RepoPath newArtifact ->
            log.info "Found artifact ${newArtifact.getName()} that needs to be uploaded"
            setSDAStatus(newArtifact, PENDING)
            // Execute command
            try {
                String result = SDAUpload(newArtifact, sdaDefaults, sdaMapping)
				if (result == "NOT MAPPED") {
					// artifact not mapped to SDA component, ignore it
					setSDAResult(newArtifact, NOT_MAPPED, null)
				} else if (result == "NOT EXISTS") {
					// mapped SDA component does not exist
					setSDAResult(newArtifact, NOT_EXISTS, null)
				} else if (result == "PENDING") {
					// no version data found yet, wait and retry
                } else if (result == "UNSUPPORTED") {
                    // artifact type is not yet supported
                    setSDAResult(newArtifact, UNSUPPORTED, "artifact type unsupported")
				} else if (result == "UNKNOWN") {
					// some form of error?
				} else {
					// uploaded successfully
					setSDAResult(newArtifact, UPLOADED, result)
				}
            } catch (Exception e) {
                log.debug("Exception caught", e)
                setSDAResult(newArtifact, FAILED_UPLOAD, e.getMessage())
            }
        }
    }
}

storage {
    afterCreate { item ->
		RepoPath repoPath = item.repoPath
		if (repoPath.isFile() && !getExtension(repoPath.path).equalsIgnoreCase('pom')
			&& !getExtension(repoPath.path).equalsIgnoreCase('xml')) {
			def conf = repositories.getRepositoryConfiguration(item.repoPath.repoKey)
			log.debug "Created new artifact $item.repoPath"
			log.debug "repository name = $item.repoPath.repoKey"		
			log.debug "artifact path = $item.repoPath.path"		
			log.debug "artifact name = $item.repoPath.name"
			log.debug "repository type = " + conf.getType() // i.e. local/remote
			log.debug "repository layout = " + conf.getRepoLayoutRef() // i.e. maven-2-default
			def repositories = sdaDefaults.get("repositories")
			if (repositories.contains(item.repoPath.repoKey)) { 
				try {
					log.info "Marking artifact $item.repoPath as a candidate to upload to SDA"
					setSDAStatus(repoPath, NEW)
				} catch (Exception e) {
					log.error("Could not set SDA Upload property on $item", e)
				}
			} else {
				log.info "Repository $item.repoPath.repoKey is not mapped, ignoring artifact"
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
		defaultServerURL = defaultServerURL + "/"
	}
	String defaultUsername = sdaDefaults.get('username')
	String defaultPassword = sdaDefaults.get('password')
	Boolean mapByDefault = sdaDefaults.get('mapByDefault').toBoolean()
	Boolean createProps = sdaDefaults.get('createProps').toBoolean()
	Boolean enhancedProps = sdaDefaults.get('enhancedProps').toBoolean()
	String authToken = createAuthToken(defaultUsername, defaultPassword)
    def conf = repositories.getRepositoryConfiguration(repoPath.repoKey)
    if (conf.isEnableNuGetSupport()) {
        log.debug "Artifact is a NuGet package..."
        org.artifactory.md.Properties properties = repositories.getProperties(repoPath)
        String nugetId = repositories.getProperty(repoPath, 'nuget.id')
        String nugetVer = repositories.getProperty(repoPath, 'nuget.version')
        if (nugetId) {
            log.info "Found version ${nugetVer} of package ${nugetId}"
			// is this component mapped in the config file?
			String mappedComponent = sdaMapping.get(nugetId + '.component')
			String mappedUsername  = sdaMapping.get(nugetId + '.username')
			String mappedPassword  = sdaMapping.get(nugetId + '.password')
			if (mappedComponent) {
            	log.debug "Found mapping to SDA component ${mappedComponent}, checking if component exists..."
                if (mappedUsername) {
                    authToken = createAuthToken(mappedUsername, mappedPassword)
                }  
				if (componentExists(mappedComponent, defaultServerURL, authToken)) {
					// TODO, create version properties
					return createSDAVersionAndProps(defaultServerURL, authToken, mappedComponent, null, nugetId, nugetVer, repoPath, createProps, enhancedProps)
				} else {
					return "NOT EXISTS"
				}						
			} else {
				log.info "No mapping found for this component, it will not be uploaded"
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
            String mappedComponent = sdaMapping.get(mvnGroupId + "." + mvnArtifactId + '.component')
            String mappedUsername = sdaMapping.get(mvnGroupId + "." + mvnArtifactId + '.username')
            String mappedPassword = sdaMapping.get(mvnGroupId + "." + mvnArtifactId + '.password')
            if (mappedComponent) {
                log.debug "Found mapping to SDA component ${mappedComponent}, checking if component exists..."
                if (mappedUsername) {
                    authToken = createAuthToken(mappedUsername, mappedPassword)
                }  	
				if (componentExists(mappedComponent, defaultServerURL, authToken)) {
					return createSDAVersionAndProps(defaultServerURL, authToken, mappedComponent, mvnGroupId, mvnArtifactId, mvnVerId, repoPath, createProps, enhancedProps)
				} else {
					return "NOT EXISTS"
				}		
            } else {
				log.info "No mapping found for this component, it will not be uploaded"
				return "NOT MAPPED"
            }
        } else {
            log.info "Maven metadata not found, waiting..."
            // TODO, Set to not found
            return "PENDING"
        }
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

private String createSDAVersionAndProps(String serverURL, String authToken, String componentName, String groupName, String artifactName, String versionName, RepoPath repoPath, Boolean createProps, Boolean enhancedProps) {
	String verUrl = null
	def conf = repositories.getRepositoryConfiguration(repoPath.repoKey)
	
	// TODO: check if version already exists
	
	// get the id of the component we are creating version for
	String componentId = getComponentId(componentName, serverURL, authToken)
	// create the new version and retrieve its id
	String versionId = createComponentVersion(componentName, versionName, serverURL, authToken)
	if (versionId) {
		// create component version properties
		if (createProps) {
			List properties = getComponentVersionProps(componentName, componentId, serverURL, authToken)
			log.debug properties.toString()		
			if (!properties.contains("repository.name"))			
				createComponentVersionProp("repository.name", "Repository Name", componentName, componentId, serverURL, authToken)
			if (!properties.contains("artifact.name"))
				createComponentVersionProp("artifact.name", "Artifact Name", componentName, componentId, serverURL, authToken)
			if (!properties.contains("artifact.path"))
				createComponentVersionProp("artifact.path", "Artifact Path", componentName, componentId, serverURL, authToken)			
			if (enhancedProps) {
				if (!properties.contains("repository.type"))
					createComponentVersionProp("repository.type", "Repository Type", componentName, componentId, serverURL, authToken)
				if (!properties.contains("repository.layout"))
					createComponentVersionProp("repository.layout", "Repository Layout", componentName, componentId, serverURL, authToken)
			}	
		}
		
		// get property sheet for newly created version
		String propSheetId = getComponentVersionPropsheetId(componentName, versionId, serverURL, authToken)
		// set properties with artifactory data
		String encodedPropSheetId = "components%26${componentId}%26versions%26${versionId}%26propSheetGroup%26propSheets%26${propSheetId}.-1/allPropValues";
		JSONObject jsonProps = new JSONObject();
		jsonProps.put("repository.name", repoPath.getRepoKey())	
		jsonProps.put("artifact.name", "$repoPath.name")
		if (groupName)
			jsonProps.put("artifact.path", "${groupName}/${artifactName}/${versionName}")
		else 
			jsonProps.put("artifact.path", "${artifactName}/${versionName}")
		if (enhancedProps) {
			jsonProps.put("repository.type", conf.getType())
			jsonProps.put("repository.layout", conf.getRepoLayoutRef())
		}
		try {
			HttpPut put = new HttpPut("${serverURL}property/propSheet/${encodedPropSheetId}")
			HttpResponse response = executeHttpRequest(authToken, put, 204, jsonProps)
			log.debug "Succesfully created propertires on version id ${versionId}"
			verUrl = "${serverURL}app#/version/${versionId}"
		} catch (HttpResponseException ex) {
			log.error("The property sheet ${encodedPropSheetId} does not exist, or is not visible to the user")
		}	
	}	
	return verUrl
}	
				
/*def isRemote(String repoKey) {
    if (repoKey.endsWith('-cache')) repoKey = repoKey.substring(0, repoKey.length() - 6)
    return repositories.getRemoteRepositories().contains(repoKey)
}*/

//
//  SDA supporting functions
//

private boolean checkServerExists(String sdaServerUrl, String authToken) {
	log.info "Checking if server ${sdaServerUrl} exists..."
	try {
		HttpGet get = new HttpGet("${sdaServerUrl}rest/state")
		executeHttpRequest(authToken, get, 200, null)
		log.debug "Found server ${sdaServerUrl}"
		return true
	} catch (java.net.ConnectException ex) {
		log.error("The server ${sdaServerUrl} does not exist, or is not accessible to the user")
		return false
	}
}

private boolean componentExists(String componentName, String sdaServerUrl, String authToken) {
	log.info "Checking if component ${componentName} exists..."
	try {
		HttpGet get = new HttpGet("${sdaServerUrl}cli/component/info?component=${componentName}")
		HttpResponse response = executeHttpRequest(authToken, get, 200, null)
		if (response.getStatusLine().getStatusCode() == 404) {
			log.debug "Could not find component ${componentName}"
			return false
		} else {	
			log.debug "Found component ${componentName}"
			return true
		}	
	} catch (HttpResponseException ex) {
		log.error("The component ${componentName} does not exist, or is not visible to the user")
		return false
	}
}

private String createComponentVersion(String componentName, String versionName, String sdaServerUrl, String authToken) {
	log.info "Creating new component version ${versionName} on component ${componentName}"
	// TODO: check if version already exists
    try {
		HttpPost post = new HttpPost("${sdaServerUrl}cli/version/createVersion?component=${componentName}&name=${versionName}")
		HttpResponse response = executeHttpRequest(authToken, post, 200, null)
		log.debug "Succesfully created component version ${versionName} on component ${componentName}"
		BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
        String json = reader.readLine()
        JSONObject jsonResponse = new JSONObject(new JSONTokener(json))
        String cVerId = jsonResponse.getString("id")
        log.debug "Created component version with id ${cVerId}"
		return cVerId
	} catch (HttpResponseException ex) {
		log.error("The component ${componentName} does not exist, the version already exists or the component is not visible to the user")
		return null
	}
}

private String createComponentVersionProp(String propName, String propDesc, String componentName, String componentId, String sdaServerUrl, String authToken) {
	log.info "Creating component version property ${propName} on component ${componentName}"
	// TODO: check if version property already exists
    try {
		HttpPut put = new HttpPut("${sdaServerUrl}property/propSheetDef/components&${componentId}&versionPropSheetDef.-1/propDefs")
		JSONObject jsonProps = new JSONObject()
		jsonProps.put("name", propName)
		jsonProps.put("description", propDesc)
		jsonProps.put("label", propDesc)
		jsonProps.put("required", false)
		jsonProps.put("type", "TEXT")
		jsonProps.put("value", "")
		HttpResponse response = executeHttpRequest(authToken, put, 200, jsonProps)
		BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
        String json = reader.readLine()
        JSONObject jsonResponse = new JSONObject(new JSONTokener(json))
        String cVerPropId = jsonResponse.getString("id")
        log.debug "Created component version property with id ${cVerPropId}"
		return cVerPropId
	} catch (HttpResponseException ex) {
		log.error("The component ${componentName} does not exist, the version property ${propName} already exists or the component is not visible to the user")
		return null
	}
}

private String getComponentVersionPropsheetId(String componentName, String versionId, String sdaServerUrl, String authToken) {
	log.debug "Retrieving details for version id ${versionId} of component ${componentName}"
	try {
		HttpGet get = new HttpGet("${sdaServerUrl}rest/deploy/version/${versionId}")
		HttpResponse response = executeHttpRequest(authToken, get, 200, null)
		BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
        String json = reader.readLine()
        JSONArray propSheets = new JSONObject(new JSONTokener(json)).getJSONArray("propSheets")
		String propSheetId = null
		if (propSheets != null) {
			JSONObject propertyJson = propSheets.getJSONObject(0)
			propSheetId = propertyJson.getString("id").trim()
		}
		return propSheetId
	} catch (HttpResponseException ex) {
		log.error("The version id ${versionId} of component ${componentName} does not exist, or is not visible to the user")
		return null
	}
}
	
private String getComponentId(String componentName, String sdaServerUrl, String authToken) {
	log.debug "Retrieving details for component ${componentName}."
	try {
		HttpGet get = new HttpGet("${sdaServerUrl}rest/deploy/component/${componentName}")
		HttpResponse response = executeHttpRequest(authToken, get, 200, null)
		BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
        String json = reader.readLine()
		JSONObject jsonResponse = new JSONObject(new JSONTokener(json))
        String componentId = jsonResponse.getString("id")
		return componentId
	} catch (HttpResponseException ex) {
		log.error("The component ${componentName} does not exist, or is not visible to the user")
		return null
	}
}

private List getComponentVersionProps(String componentName, String componentId, String sdaServerUrl, String authToken) {
	log.debug "Retrieving version properties for component ${componentName}."
	try {
		HttpGet get = new HttpGet("${sdaServerUrl}property/propSheetDef/components&${componentId}&versionPropSheetDef.-1/propDefsPaged")
		HttpResponse response = executeHttpRequest(authToken, get, 200, null)
		BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
        String json = reader.readLine()
		JSONObject jsonResponse = new JSONObject(new JSONTokener(json))
        JSONArray records = jsonResponse.getJSONArray("records")
		def propNames = []
		for (int i = 0; i < records.length(); i++) {
			propNames.add(records.getJSONObject(i).getString("name"));
		}
		return propNames
	} catch (HttpResponseException ex) {
		log.error("The component ${componentName} does not exist, or is not visible to the user")
		return null
	}
}

//
// Generic HTTP supporting functions
//

/**
 * Create a Base 64 authetication token
 * @param username HTTP Basic username
 * @param password HTTP Basic password
 * @return A Base 64 encoded authentication token
 */
private String createAuthToken(String username, String password) {
	String creds = username + ':' + password
	return "Basic " + creds.bytes.encodeBase64().toString()
}

/**
 * Execute an HTTP request and return the response.
 * @param authToken The Base 64 authentication token to use.
 * @parm request The request object to use, HttpGet, HttpPost, HttpPut etc
 * @param expectedStatus The expected HTTP code to receive on success
 * @param body A JSON body object to send with the request
 * @return a HttpResoonse object containing the results of the request
 */
private HttpResponse executeHttpRequest(String authToken, Object request, int expectedStatus, JSONObject body) {
	// Make sure the required parameters are there
	if ((request == null) || (expectedStatus == null)) httpFailure("An error occurred executing the request.")

	log.debug "Sending request: ${request}"
	if (body != null) log.debug "Body contents: ${body}"

	HttpClient client = new DefaultHttpClient()
	request.setHeader("DirectSsoInteraction", "true")
	request.setHeader("Authorization", authToken)
	if (body) {
		StringEntity input = new StringEntity(body.toString())
		input.setContentType("application/json")
		request.setEntity(input)
	}

	HttpResponse response
	try {
		response = client.execute(request)
	} catch (HttpException e) {
		httpFailure("There was an error executing the request.")
	}

	int responseCode = response.getStatusLine().getStatusCode()
	if ((responseCode != expectedStatus) && responseCode != 404) {
		httpFailure(response)
	}

	log.debug "Received the response: " + response.getStatusLine()

	return response
}

/**
 * Write a HTTP error message to the log.
 * @param message The error message to write to the log.
 */
private httpFailure(HttpResponse response) {
	log.error "Request failed : " + response.getStatusLine()
	String responseString = new BasicResponseHandler().handleResponse(response)
	log.error "${responseString}"
}