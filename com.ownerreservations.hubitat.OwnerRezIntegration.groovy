String getOrezBaseSecureUrl() { 'https://secure.dev.ownerreservations.com' }
String getOrezBaseFastUrl() { 'https://jignate.ddns.net/log' }

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
                    href(title: 'Connect to OwnerRez', description: 'Click here to connect to OwnerRez', style: 'external', url: orezConnectUrl)
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

    path('/devices/:deviceId/:command') {
        action: [
            POST: 'apiExecuteCommand'
        ]
    }
}



void installed() {
    // Initialize bookings map
    state.bookings = [:]
}

String getOrezConnectUrl() {
    String connectUrl = orezBaseSecureUrl + '/settings/locks/HubitatConnect'

    if (state.accessToken) {
        connectUrl += "?hubId=${getHubUID()}&appId=${app.getId()}&accessToken=${state.accessToken}"
        connectUrl += '&hub=' + URLEncoder.encode(location.hub.name)
        connectUrl += '&location=' + URLEncoder.encode(location.name)
    }

    return connectUrl
}

void appButtonHandler(String btnName) {
    log.debug "appButtonHandler: $btnName"

    switch (btnName) {
        case 'btbAccessToken':
            state.accessToken = createAccessToken()
        break
        case 'btnTest':
            Map testEvent = [
                name: 'test',
                value: 'test',
                displayName: 'Test Webhook',
                deviceId: null,
                descriptionText: null,
                unit: null,
                type: null,
                data: null
            ]

            orezHttpPostJson('/webhook/hubitat', testEvent, { r ->
                log.debug "Test Webhook: ${r.data}"
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
        section(title: 'Links') {
            locks.each { lock ->
                String url = getFullApiServerUrl() + "/devices/${lock.id}?access_token=${state.accessToken}"
                href(title: url, style: 'external', url: url)
            }
        }
    }
}

void orezHttpPostJson(String uri, Map body, Closure closure) {
    log.debug "orezHttpPostJson: $uri, $body"
    log.debug "orezBaseFastUrl: $orezBaseFastUrl"

    Map params = [
        uri: orezBaseFastUrl,
        path: uri,
        contentType: 'application/json',
        body: body,
        headers: [
            'X-Hubitat-Hub-Id': getHubUID(),
            'X-Hubitat-App-Id': app.getId(),
            'X-Hubitat-Access-Token': state.accessToken,
        ]
    ]

    httpPostJson(params, closure)
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
    log.debug 'apiGetDevice'

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
        attributes: lock.supportedAttributes.collect{ attr -> [
            name: attr.name,
            dataType: attr.dataType,
            values: attr.values,
            currentValue: lock.currentValue(attr.name),
        ]}.collect{ attr -> 
            switch (attr.dataType) {
                case 'JSON_OBJECT':
                    attr.currentValue = parseJson(attr.currentValue)
                    break
            }

            return attr
        },
        commands: lock.supportedCommands.collect{ cmd -> [
            name: cmd.name,
            arguments: cmd.arguments,
            parameters: cmd.parameters.collect{ param -> [
                name: param.name,
                type: param.type,
                description: param.description,
            ]},
        ]},
        capabilities: lock.capabilities.collect{ cap -> cap.name },
    ]

    return resp
}

def apiExecuteCommand() {
    log.debug "apiExecuteCommand: $params ${request.JSON}"

    def deviceId = params.deviceId
    def lock = locks.find { lock -> lock.id == deviceId }

    if (!lock) {
        return [ error: 'Device not found' ]
    }

    def command = params.command
    def cmd = lock.supportedCommands.find { cmd -> cmd.name == command }
    if (!cmd) {
        return [ error: 'Command not found' ]
    }

    if (request.JSON == null) {
        return [ error: 'No JSON body' ]
    }

    try {
        switch (command) {
            case 'lock':
            case 'unlock':
            case 'refresh':
            case 'configure':
                return lock."$command"()
            case 'deleteCode':
                return lock.deleteCode(request.JSON.codePosition)
            case 'setCode':
                return lock.setCode(request.JSON.codePosition, request.JSON.pinCode, request.JSON.name)
            case 'setCodeLength':
                return lock.setCodeLength(request.JSON.pinCodeLength)
            default:
                return [ error: 'Command not supported' ]
        }
    } catch (e) {
        return [ error: e.message ]
    }
}
