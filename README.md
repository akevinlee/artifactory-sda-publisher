# artifactory-sda-publisher

This plugin allows you to automatically create new component versions in Serena Deployment Automation whenever a new artifact is added to Artifactory. It currently supports both Maven and NuGet repository types.
This plugin (by design) does not upload the artifacts to SDA's artifact repository, it relies on them being stored and resolved from artifactory using the metadata stored in SDA.

**This is a work in progress and should not be used in production yet**

# How it works

The plugin monitors artifactory for new versions. If a new version is uploaded (and it is not a directory, or metadata file) a property `sda.status` with value `NEW` is applied to the artifact. This is required because artifactory does not apply NuGet metadata instantly but on a schedule, so we need this to be in place before we upload the version. On the plugins own schedule it looks for artifacts with `sda.status=NEW` value and then determines what type of artifact it is, Maven, NuGet etc. It retrieves the properties/metadata from artifactory and then creates a new component version in SDA. The component version properties `repository.path` and `artifact.name` are updated in SDA. These are used to store the reference back to artifactory from SDA. If the upload is successful the `sda.status` property is set to `UPLOADED` and the `sda.url` property is set to the URL of the new component version in SDA. If the upload fails,the component is not mapped or does not exist in SDA then the `sda.status` property is set accordingly.

# Using the Plugin

## Installation

1. Stop artifactory
2. Click on **Download ZIP** to download all the files.
3. Unzip the downloaded file and copy the contents of the `artifactory-sda-publisher` directory into your `*artifactory_home*/etc/plugins` directory.
4. Edit the configuation file `sda.config` with the details of your SDA connection details and the components you want mapped from artifactory (see below).
5. Start artifactory

## Example Setup

1. Create a component in SDA, e.g. `gson`
2. Create two version properties on the component: `repository.path` and `artifact.name`. These will be useed to store the reference back to artifactory from SDA
3. Add a mapping in the configuration file from artifactory to SDA (see below)
4. Upload a new artifact into Artifactory

## Example configuration file

An example configuration file is shown below. The `serverURL`, `username` and `password` are required. A new version will only be created in SDA if there is a entry in the `mapping` section that contains the *path* of the artifacts in Artifactory to an existing *component* in SDA.

```
defaults {
	serverURL = "http://localhost:8080/serena_ra"
	username = "admin"
	password = "admin"
	mapByDefault = false 		// NOT YET USED
	createProps = false 		// NOT YET USED
	enhancedProps = false 		// NOT YET USED
}	
mapping {
	"Microsoft.Web.Infrastructure" {
		component = "ms-web-infra"
		username = "admin"
		password = "admin"
		status = "BUILT" 		// NOT YET USED
		process = "Deploy" 		// NOT YET USED
	}	
	"com.google.collections.google-collections" {
		component = "google-collections"
	}
	com {
		google {
			code {
				gson {
					component = "gson"
				}
			}
		}
	}
}
```

## Uploading existing artifacts

To upload existing artifacts you can add the property `sda.status` with value `NEW` to the artifact version. The plugin should then pick this up and upload it.

## Debugging the plugin

All output from the plugin will be added to `*artifactory_home*/logs/artifactory.log`. To enable debug output for the plugin add the following to `*artifactory_home*/etc/logback.xml` 

```
	<logger name="sda-publisher">
        <level value="debug"/>
    </logger>
```	

## Updating the plugin

If you want to make changes to the plugin then you can configure artifactory to pick up the changes automatically by editing `*artifactory_home*/etc/artifactory.system.properties` and uncommenting out the following line:

```
artifactory.plugin.scripts.refreshIntervalSecs=10
```

Since currently the configuration file is only loaded on startup, you can make a "mock" change to the plugin to reload the configuration.

# Limitations

* Components need to exist in SDA with component version properties already created
* Plugin needs to be updated to reload the configuration

# TODO

1. Automatically create the version properties on a component if they do not exist.
2. Allow the upload of artifact to an SDA component without a "mapping" entry if the artifact name and component name match.
3. Create "enhanced" properties with more metadata about the artifact being uploaded (would need 1 above).
3. Allow the status of the version to be set.
4. Allow an Application/Component process to be executed on upload.
5. Allow the configuration to be reloaded by monitoring the file, or by invoking a REST call on the plugin.

