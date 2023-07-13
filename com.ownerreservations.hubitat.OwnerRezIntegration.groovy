definition(
    name: 'OwnerRez Integration',
    namespace: 'com.ownerreservations.hubitat',
    author: 'OwnerRez, Inc',
    description: 'OwnerRez Hubitat Integration',
    category: 'Convenience',
    oauth: true,
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true,
)

preferences {
    page(name: 'main', title: 'Setup', install: true, uninstall: true) {
        section(title: 'Setup') {
            input(name: 'locks', type: 'capability.lock', title: 'Locks', submitOnChange: true, multiple: true)
        }

        section(title: 'Connection') {
            if (state.accessToken) {
                if (!settings.locks || settings.locks.length == 0) {
                    paragraph 'Please configure the locks before connecting to OwnerRez.'
                }
                else {
                    paragraph 'Endpoint: ' + getFullApiServerUrl()
                    paragraph 'Access Token: ' + state.accessToken
                    href(title: 'Connect to OwnerRez', description: 'Click here to connect to OwnerRez', style: 'external', url: orezConnectUrl())
                }
            }
            else {
                paragraph 'Please generate an access token first.'
                input(name: 'btbAccessToken', type: 'button', title: 'Create Access Token')
            }
        }
    }

    page(name: 'debug', title: 'Debug')
}

mappings {
    path('/info') {
        action: [
            GET: 'apiGetInfo'
        ]
    }

    path('/devices') {
        action: [
            GET: 'apiGetDevices'
        ]
    }

    path('/devices/:deviceId') {
        action: [
            GET: 'apiGetDevice'
        ]
    }
}



void installed() {
}

String orezConnectUrl() {
    String baseUrl = 'https://secure.dev.ownerreservations.com/settings/locks/HubitatConnect'

    if (!this.createAccessToken) {
        baseUrl += "?hubId=${getHubUID()}&appId=${app.getId()}&accessToken=${state.accessToken}"
        baseUrl += '&location=' + URLEncoder.encode(location.name)
        baseUrl += '&hub=' + URLEncoder.encode(location.hub.name)
    }

    return baseUrl
}

void appButtonHandler(String btnName) {
    switch (btnName) {
        case 'btbAccessToken':
            state.accessToken = createAccessToken()
        case 'btnTest':
            httpPost('https://jignate.ddns.net/log/webhook/hubitat', 'oh hi mark', { r ->
                log.debug r
            })
            break
    }
}

def debug() {
    dynamicPage(name: 'debug', title: 'Debug') {
        section {
            input(name: 'btbAccessToken', type: 'button', title: 'Create Access Token')
            input(name: 'btnTest', type: 'button', title: 'Test Webhook')
        }
        section(title: "Links") {
            locks.each { lock ->
                String url = getFullApiServerUrl() + "/devices/${lock.id}?access_token=${state.accessToken}"
                href(title: url, style: 'external', url: url)
            }
        }
    }
}

Map apiGetInfo() {
    return [
        hubId: getHubUID(),
        appId: app.getId(),
        endpoint: getFullApiServerUrl(),
        location: location.name,
        name: location.hub.name,
    ]
}

List apiGetDevices() {
    List resp = []

    locks.each { lock ->
        resp << [id: lock.id, name: lock.name, type: lock.typeName, label: lock.label]
    }

    return resp
}

Map apiGetDevice() {
    log.debug "apiGetDevice: $params"

    def deviceId = params.deviceId
    def lock = locks.find { lock -> lock.id == deviceId }

    if (!lock) {
        return [ error: 'Device not found' ]
    }

    Map resp = [
        id: lock.id,
        name: lock.name,
        type: lock.typeName,
        label: lock.label,
        displayName: lock.displayName,
        attributes: lock.supportedAttributes.collect({ attr -> [
            name: attr.name,
            dataType: attr.dataType,
            values: attr.values,
            currentValue: lock.currentValue(attr.name),
        ]}).collect({ attr -> 
            switch (attr.dataType) {
                case 'JSON_OBJECT':
                    attr.currentValue = parseJson(attr.currentValue)
                    break
            }

            return attr
        }),
        commands: lock.supportedCommands.collect({ cmd -> [
            name: cmd.name,
            arguments: cmd.arguments,
            parameters: cmd.parameters.collect({ param -> [
                name: param.name,
                type: param.type,
                description: param.description,
            ]}),
        ]}),
        capabilities: lock.capabilities.collect({ cap -> cap.name }),
    ]

    return resp
}
