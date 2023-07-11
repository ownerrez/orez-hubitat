definition(
    name: "OwnerRez Integration",
    namespace: "com.ownerreservations.hubitat",
    author: "OwnerRez, Inc",
    description: "OwnerRez Hubitat Integration",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage", title: "OwnerRez", install: true, uninstall: true) {
        section {
            paragraph "Endpoint: " + getFullApiServerUrl()
            paragraph "Access Token: " + state.accessToken
            input(name: "btbAccessToken", type: "button", title: "Create Access Token")
            input(name: "btnTest", type: "button", title: "Test Webhook")
            input(name: "locks", type: "capability.lock", title: "Locks")
        }
    }
}

mappings {
    path("/devices") {
        action: [
            GET: "apiGetDevices"
        ]
    }
}

def appButtonHandler(String btnName) {
    switch(btnName) {
        case "btbAccessToken":
            state.accessToken = createAccessToken()
        case "btnTest":
            httpPost("https://jignate.ddns.net/log/webhook/hubitat", "oh hi mark", { r ->
                log.debug r
            })
        break
    }
}

def apiGetDevices() {
    def resp = []
    locks.each {
        resp << [id: it.id, name: it.name, type: it.typeName, label: it.label]
    }
    return resp
}
