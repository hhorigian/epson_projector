/**
 *  Hubitat - Epson IP Projector Driver
 *
 *  Copyright 2025 VH - Vhorigian
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Licensed under the Apache License, Version 2.0
 *
 *  1.0 - 2025 - Power On / Power Off / Switch Input
 */

metadata {
    definition(name: "Epson IP Projector", namespace: "VH", author: "VH") {
        capability "Actuator"
        capability "Switch"

        command "sendPowerOnCommand"
        command "sendPowerOffCommand"
        command "switchInput", [[name: "Input*", type: "ENUM", constraints: [
            "HDMI1",
            "HDMI2",
            "HDBaseT",
            "LAN",
            "Computer"
        ]]]
    }
}

preferences {
    section("Device Settings") {
        input "ipAddress", "text", title: "IP Address", required: true
        input "password", "password", title: "Web Control Password", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "UserGuide", type: "hidden", title: fmtHelpInfo("Manual do Driver")
    }
}

import groovy.transform.Field
@Field static final String DRIVER = "by TRATO"
@Field static final String USER_GUIDE = "https://github.com/hhorigian/"

String fmtHelpInfo(String str) {
    String prefLink = "<a href='${USER_GUIDE}' target='_blank'>${str}<br><div style='font-size: 70%;'>${DRIVER}</div></a>"
    return "<div style='font-size: 160%; font-style: bold; padding: 2px 0px; text-align: center;'>${prefLink}</div>"
}

@Field static final Map INPUT_CODES = [
    "HDMI1"   : "30",
    "HDMI2"   : "A0",
    "HDBaseT" : "80",
    "LAN"     : "41",
    "Computer": "14"
]

def installed() {
    log.debug "Installed"
    runIn(1800, logsOff)
}

def updated() {
    log.debug "Updated"
}

def parse(String description) {
    // não usado
}

def on() {
    sendPowerOnCommand()
}

def off() {
    sendPowerOffCommand()
}

def sendPowerOnCommand() {
    sendEscVp21Command("PWR+ON")
    sendEvent(name: "switch", value: "on")
}

def sendPowerOffCommand() {
    sendEscVp21Command("PWR+OFF")
    sendEvent(name: "switch", value: "off")
}

def switchInput(String inputName) {
    def code = INPUT_CODES[inputName]
    if (!code) {
        log.error "Input desconhecido: ${inputName}"
        return
    }
    log.info "Trocando para input: ${inputName} (código ${code})"
    sendEscVp21Command("SOURCE+${code}")
}

private void sendEscVp21Command(String cmd) {
    def uri = "http://${ipAddress}/api/v01/control/escvp21?cmd=${cmd}"
    if (logEnable) log.debug "Enviando comando: ${uri}"

    def authParams = getDigestAuthParams(uri)

    if (!authParams) {
        log.error "Falha ao obter parâmetros de autenticação Digest"
        return
    }

    def digestAuth = generateDigestAuth(uri, "EPSONWEB", password, authParams)
    if (!digestAuth) {
        log.error "Falha ao gerar header de autenticação Digest"
        return
    }

    def params = [
        uri    : uri,
        headers: ["Authorization": digestAuth]
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                if (logEnable) log.debug "Comando enviado com sucesso. Resposta: ${resp.data}"
            } else {
                log.error "Falha no comando. HTTP status: ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "Exceção ao enviar comando: ${e.message}"
    }
}

private Map getDigestAuthParams(String uri) {
    def params = [:]
    try {
        httpGet([uri: uri]) { resp -> }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 401) {
            def authHeader = e.response?.headers?.getAt("WWW-Authenticate")?.toString()
            if (authHeader) {
                params.realm  = extractValue(authHeader, "realm")
                params.nonce  = extractValue(authHeader, "nonce")
                params.opaque = extractValue(authHeader, "opaque")
                params.qop    = extractValue(authHeader, "qop")
            } else {
                log.error "Header WWW-Authenticate não encontrado"
            }
        } else {
            log.error "Status inesperado ao obter auth: ${e.statusCode}"
        }
    } catch (Exception e) {
        log.error "Exceção ao obter auth params: ${e.message}"
    }
    return params
}

private String generateDigestAuth(String uri, String user, String pass, Map authParams) {
    def realm  = authParams.realm
    def nonce  = authParams.nonce
    def opaque = authParams.opaque

    if (!realm || !nonce) return null

    def ha1      = md5("${user}:${realm}:${pass}")
    def ha2      = md5("GET:${uri}")
    def response = md5("${ha1}:${nonce}:${ha2}")

    return "Digest username=\"${user}\", realm=\"${realm}\", nonce=\"${nonce}\", uri=\"${uri}\", response=\"${response}\"" +
           (opaque ? ", opaque=\"${opaque}\"" : "")
}

private String extractValue(String header, String key) {
    def matcher = (header =~ /${key}="([^"]+)"/)
    return matcher ? matcher[0][1] : null
}

private String md5(String input) {
    return java.security.MessageDigest.getInstance("MD5").digest(input.bytes).encodeHex().toString()
}

def logsOff() {
    log.warn "Debug logging desativado"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
