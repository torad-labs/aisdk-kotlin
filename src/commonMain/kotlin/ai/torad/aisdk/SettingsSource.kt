package ai.torad.aisdk

internal object SettingsSource {

    fun loadApiKey(
        apiKey: String?,
        environmentVariableName: String,
        apiKeyParameterName: String = "apiKey",
        description: String,
        environment: Map<String, String> = emptyMap(),
    ): String =
        apiKey
            ?: environment[environmentVariableName]
            ?: throw LoadAPIKeyError(
                "$description API key is missing. Pass it using the '$apiKeyParameterName' parameter " +
                    "or provide $environmentVariableName through the host environment map.",
            )

    fun loadSetting(
        settingValue: String?,
        environmentVariableName: String,
        settingName: String,
        description: String,
        environment: Map<String, String> = emptyMap(),
    ): String =
        settingValue
            ?: environment[environmentVariableName]
            ?: throw LoadSettingError(
                "$description setting is missing. Pass it using the '$settingName' parameter " +
                    "or provide $environmentVariableName through the host environment map.",
            )

    fun loadOptional(
        settingValue: String?,
        environmentVariableName: String,
        environment: Map<String, String> = emptyMap(),
    ): String? =
        settingValue ?: environment[environmentVariableName]
}
