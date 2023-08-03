import groovy.json.JsonOutput

String getOrezBaseSecureUrl() { 'https://secure.dev.ownerreservations.com' }
String getOrezBaseFastUrl() { 'https://jignate.ddns.net/log' }
String getOrezAppVersion() { '1.0.0-alpha' } // major.minor.patch[-prerelease] 

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
            input(name: 'locks', type: 'capability.lockCodes', title: 'Locks', submitOnChange: true, multiple: true)
        }

        section(title: 'Connection') {
            if (state.accessToken) {
                if (!settings.locks || settings.locks.length == 0) {
                    paragraph 'Please configure the locks before connecting to OwnerRez.'
                }
                else if (!state.accessToken) {
                    paragraph 'Please click "Done" to complete setup before connecting to OwnerRez.'
                }
                else {
                    paragraph 'Endpoint: ' + getFullApiServerUrl()
                    paragraph 'Access Token: ' + state.accessToken
                    href(title: 'Connect to OwnerRez', description: 'Click here to connect to OwnerRez', style: 'external', url: orezConnectUrl)
                }
            }
        }
    }

    // Debug page must be navigated to manually
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

    path('/sync') {
        action: [
            PUT: 'apiSync'
        ]
    }

    path('/sync/:bookingId') {
        action: [
            POST: 'apiSyncBooking',
            PUT: 'apiSyncBooking',
            DELETE: 'apiSyncBooking',
        ]
    }
}

// Only gets called when the user clicks "Done" on the main page
void installed() {
    log.debug 'installed'

    // Initialize bookings map
    state.bookings = [:]
    state.accessToken = createAccessToken()

    subscribeToEvents()
    scheduleEvents(null)
}

void updated() {
    log.debug 'updated'

    if (!state.bookings) {
        state.bookings = [:]
    }

    if (!state.accessToken) {
        state.accessToken = createAccessToken()
    }

    Map bookings = helperGetBookings(state.bookings)

    subscribeToEvents()
    scheduleEvents(bookings)
}

void subscribeToEvents() {
    log.debug 'subscribeToEvents'

    // Remove old subscriptions from potentially removed locks
    unsubscribe()

    // Separate handler for each event for future flexibility
    subscribe(locks, 'lock', lockHandler)
    subscribe(locks, 'unlock', lockHandler)
    subscribe(locks, 'lastCodeName', lastCodeNameHandler)
    subscribe(locks, 'lockCodes', lockCodesHandler)
    subscribe(locks, 'codeChanged', codeChangedHandler)
    subscribe(locks, 'maxCodes', maxCodesHandler)
    subscribe(locks, 'codeLength', codeLengthHandler)
}

void scheduleEvents(Map bookings) {
    log.debug 'scheduleEvents'

    // Remove old scheduled tasks
    unschedule()

    // Schedule tasks
    if (bookings) {
        Map nextBooking = helperFindNextBooking(bookings, false)
        if (nextBooking) {
            // Schedule reconcileDoorCodes for next booking
            runOnce(nextBooking.checkIn, 'reconcileDoorCodes', [overwrite: false])
            runOnce(nextBooking.checkOut, 'reconcileDoorCodes', [overwrite: false])
        }
    }
}

void reconcileDoorCodes(Map bookings) {
    log.debug 'reconcileDoorCodes'

    // Iterate through all locks
    locks.each { lock ->
        log.trace "reconcileDoorCodes: ${lock.name}"
        // Get the lock's current codes
        Map codes = parseJson(lock.currentValue('lockCodes'))
        log.trace "reconcileDoorCodes: ${codes}"
        Map currentCodes = helperOnlyOrezCodes(codes)

        // Remove all codes that are not in the current bookings
        currentCodes.each { code ->
        }
    }

}

void webhook(e) {
    log.debug "webhook: ${e.name}"

    // Normalize the event payload
    Map payload = [
        id: e.id,
        deviceId: e.deviceId,
        name: e.name,
        displayName: e.displayName,
        source: e.source,
        value: e.value,
        data: e.jsonData,
        descriptionText: e.descriptionText,
        isStateChange: e.isStateChange,
        type: e.type,
        date: e.date,
        unit: e.unit,
    ]

    orezHttpPostJson('/webhook/hubitat', payload, { r ->
        log.debug "Webhook: ${r.data}"
    })
}

void lockHandler(e) {
    webhook(e)
}

void lastCodeNameHandler(e) {
    webhook(e)
}

void lockCodesHandler(e) {
    webhook(e)
}

void codeChangedHandler(e) {
    webhook(e)
}

void maxCodesHandler(e) {
    webhook(e)
}

void codeLengthHandler(e) {
    webhook(e)
}

String getOrezConnectUrl() {
    String connectUrl = orezBaseSecureUrl + '/settings/locks/HubitatConnect'

    if (state.accessToken) {
        connectUrl += '?'
        connectUrl += '&hubId=' + URLEncoder.encode(hubUID)
        connectUrl += '&appId=' + URLEncoder.encode(app.id.toString())
        connectUrl += '&accessToken=' + URLEncoder.encode(state.accessToken)
        connectUrl += '&version=' + URLEncoder.encode(orezAppVersion)
        connectUrl += '&hub=' + URLEncoder.encode(location.hub.name)
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
            paragraph "helperFindNextBooking: ${helperFindNextBooking(state.bookings)}"
        }
        section {
            input(name: 'btbAccessToken', type: 'button', title: 'Create Access Token')
            input(name: 'btnTest', type: 'button', title: 'Test Webhook')
        }
        section(title: 'Links') {
            locks.each { lock ->
                String url = fullApiServerUrl + "/devices/${lock.id}?access_token=${state.accessToken}"
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
            'X-Hubitat-Hub-Id': hubUID,
            'X-Hubitat-App-Id': app.id,
            'X-Hubitat-Access-Token': state.accessToken,
            'X-Hubitat-Orez-Version': orezAppVersion,
        ]
    ]

    httpPostJson(params, closure)
}

Map orezHttpResponseJson(def data) {
    return [
        renderMethod: true,
        contentType: 'application/json',
        headers: [
            'X-Hubitat-Orez-Version': orezAppVersion,
        ],
        data: JsonOutput.toJson(data),
    ]
}

Map apiGetInfo() {
    log.debug 'apiGetInfo'

    return orezHttpResponseJson([
        hubId: hubUID,
        appId: app.id,
        endpoint: fullApiServerUrl,
        version: orezAppVersion,
        location: location.name,
        name: location.hub.name,
        bookings: state.bookings,
        nextBooking: helperFindNextBooking(state.bookings),
    ])
}

Map apiGetDevices() {
    log.debug 'apiGetDevice'

    List resp = []

    locks.each { lock ->
        resp << [id: lock.id, name: lock.name, type: lock.typeName, label: lock.label]
    }

    return orezHttpResponseJson(resp)
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
        attributes: lock.supportedAttributes.collect { attr ->
        [
            name: attr.name,
            dataType: attr.dataType,
            values: attr.values,
            currentValue: lock.currentValue(attr.name),
        ]}.collect { attr ->
            switch (attr.dataType) {
                case 'JSON_OBJECT':
                    attr.currentValue = parseJson(attr.currentValue)
                    break
            }

            return attr
        },
        commands: lock.supportedCommands.collect { cmd -> [
            name: cmd.name,
            arguments: cmd.arguments,
            parameters: cmd.parameters.collect { param -> [
                name: param.name,
                type: param.type,
                description: param.description,
            ]},
        ]},
        capabilities: lock.capabilities.collect { cap -> cap.name },
    ]

    return orezHttpResponseJson(resp)
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

Map apiSync() {
    log.debug "apiSync ${request.JSON}"

    state.bookings = helperGetBookings(request.JSON)
    scheduleEvents(state.bookings)
    reconcileDoorCodes(state.bookings)

    return orezHttpResponseJson([
        bookings: state.bookings,
        nextBooking: helperFindNextBooking(state.bookings, false),
    ])
}

Map apiSyncBooking() {
    log.debug "apiSync ${request.method} ${param.bookingId} ${request.JSON}"

    return orezHttpResponseJson([
        bookings: state.bookings,
        nextBooking: helperFindNextBooking(state.bookings),
    ])
}

// Help functions

// Format booking, and only return future bookings
Map helperGetBookings(Map bookings) {
    log.debug 'helperGetBookings'

    Date now = new Date()

    return bookings.collect { key, booking ->
        log.trace "helperGetBookings state.bookings.collect ${key} ${booking}"
        booking.checkIn = toDateTime(booking.checkIn)
        booking.checkOut = toDateTime(booking.checkOut)
        return booking
    }
    .findAll { booking ->
        return booking.checkIn >= now || booking.checkOut >= now
    }
    .collectEntries { booking ->
        log.trace "helperGetBookings state.bookings.collectEntries ${booking}"
        return [ booking.id, booking ]
    }
}

Map helperFindNextBooking(Map bookings, boolean format = true) {
    log.debug 'helperFindNextBooking'

    Map _bookings = format ? helperGetBookings(bookings) : bookings

    return _bookings.inject(null) { nextBooking, key, booking ->
        if (nextBooking == null) {
            return booking
        }

        if (booking.checkIn < nextBooking.checkIn) {
            return booking
        }

        return nextBooking
    }
}

Map helperOnlyOrezCodes(Map codes) {
    log.debug 'helperOnlyOrezCodes'
    
    return codes
        .findAll { key, code -> code.name.startsWith('ORB') }
        .collectEntries { key, code ->
            // Split name on dash to get bookingId
            String[] parts = code.name.split('-')
            code.key = key
            code.bookingId = parts[0]
            return [ key, code ]
        }
}
