/**
 *  Hubitat - Epson Projector TCP Driver (ESC/VP21)
 *
 *  Adaptado para modelos Epson mais antigos, como HC 5040UB / 5040U,
 *  usando comando TCP direto em vez da Web API /api/v01/control/escvp21
 *
 *  by TRATO / VH

 * Versão 1.0 - 4/2/2026 - V1. Usa a porta 4352 e comandos via tcp. Não tem web control.

 */

import groovy.transform.Field

metadata {
    definition(name: "Epson Projector TCP", namespace: "VH", author: "VH") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Initialize"

        attribute "powerStatus", "string"
        attribute "input", "string"
        attribute "connection", "string"
        attribute "lastResponse", "string"

        command "queryInputList"
        command "sendPowerOnCommand"
        command "sendPowerOffCommand"
        command "switchInput", [[name: "Input*", type: "ENUM", constraints: [
            "HDMI1",
            "HDMI2",
            "HDBaseT",
            "LAN",
            "Computer"
        ]]]
        command "connectSocket"
        command "disconnectSocket"
        command "sendRawCommand", [[name: "Raw Command*", type: "STRING"]]
    }

    preferences {
        section("Device Settings") {
            input "ipAddress", "text", title: "IP Address", required: true
            input "tcpPort", "number", title: "TCP Port", defaultValue: 4352, required: true
            input "autoDisconnect", "bool", title: "Disconnect after each command", defaultValue: false
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
            input "pollInterval", "number", title: "Polling (minutos)", defaultValue: 5, required: true
        }
    }
}

@Field static final String DRIVER = "by TRATO"
@Field static final Map INPUT_CODES = [
    "HDMI1"   : "30",
    "HDMI2"   : "A0",
    "HDBaseT" : "80",
    "LAN"     : "41",
    "Computer": "14"
]

@Field static final Map INPUT_NAMES = [
    "30": "HDMI1",
    "A0": "HDMI2",
    "80": "HDBaseT",
    "41": "LAN",
    "14": "Computer"
]

def installed() {
    log.info "Installed"
    initialize()
   schedulePolling()    
}

def updated() {
    log.info "Updated"
    unschedule()

    schedulePolling()

    if (logEnable) runIn(1800, "logsOff")
}

def initialize() {
    sendEvent(name: "connection", value: "disconnected")
}

def schedulePolling() {
    unschedule("pollStatus")

    if (!pollInterval || pollInterval.toInteger() <= 0) {
        log.warn "Polling desativado"
        return
    }

    def minutes = pollInterval.toInteger()

    log.info "Agendando polling a cada ${minutes} minuto(s)"

    schedule("0 */${minutes} * ? * *", pollStatus)
}

def pollStatus() {
    if (logEnable) log.debug "Executando polling"

    refreshPower()

    def status = device.currentValue("powerStatus")
    if (status == "on") {
        runIn(5, "refreshInput")
    }
}


private boolean isPJLinkError(String val) {
    return ["ERR1", "ERR2", "ERR3", "ERR4"].contains(val)
}


def queryInputList() {
    sendPJLinkCommand("%1INST ?")
}


def switchInput(String inputName) {
    String code

    switch (inputName) {
        case "HDMI1":
            code = "33"
            break
        case "HDMI2":
            code = "32"
            break
        case "LAN":
            code = "52"
            break
        case "Computer":
            code = "11"
            break
        default:
            log.error "Input desconhecido: ${inputName}"
            return
    }

    log.info "Trocando input para ${inputName} (${code})"
    sendPJLinkCommand("%1INPT ${code}")

    runIn(3, "refreshInput")
}


def parse(String message) {
    if (message == null) return

    if (logEnable) log.debug "parse() bruto: ${message}"

    String msg = message

    // Se vier em HEX, converte para ASCII
    if (message ==~ /(?i)^[0-9a-f]+$/ && (message.length() % 2 == 0)) {
        try {
            byte[] bytes = message.decodeHex()
            msg = new String(bytes, "UTF-8")
        } catch (Exception e) {
            log.warn "Falha ao converter HEX para texto: ${e.message}"
        }
    }

    msg = msg?.trim()

    if (!msg) return

    if (logEnable) log.debug "parse() texto: ${msg}"
    sendEvent(name: "lastResponse", value: msg)

    if (msg.startsWith("PJLINK ")) {
        log.info "Banner PJLink recebido: ${msg}"

        if (msg == "PJLINK 0") {
            sendEvent(name: "pjlinkAuth", value: "off")
        } else if (msg.startsWith("PJLINK 1")) {
            sendEvent(name: "pjlinkAuth", value: "on")
            log.warn "PJLink com autenticação habilitada"
        }
        return
    }

        if (msg.startsWith("%1INPT=")) {
            String val = msg.split("=")[1]?.trim()

            if (val == "ERR3") {
                log.warn "INPT indisponível (ERR3) → tentando novamente em 2s"
                runIn(2, "refreshInput")
                return
            }

            if (isPJLinkError(val)) {
                log.warn "Erro PJLink em INPT: ${val}"
                return
            }

            String inputName
            switch (val) {
                case "11": inputName = "Computer"; break
                case "32": inputName = "HDMI2"; break
                case "33": inputName = "HDMI1"; break
                case "52": inputName = "LAN"; break
                default: inputName = "Input ${val}"
            }

            sendEvent(name: "inputCode", value: val)
            sendEvent(name: "input", value: inputName)

            log.info "Input atual: ${inputName} (${val})"
            return
        }

        if (msg.startsWith("%1INST=")) {
            String val = msg.split("=")[1]?.trim()
            log.info "Entradas suportadas pelo projetor: ${val}"
            sendEvent(name: "inputList", value: val)
            return
        }

    if (msg.startsWith("ERR")) {
        log.warn "Resposta de erro do PJLink: ${msg}"
        return
    }

    log.info "Resposta não tratada: ${msg}"
}

private void handlePowerResponse(String code) {
    switch (code) {
        case "00":
            sendEvent(name: "switch", value: "off")
            sendEvent(name: "powerStatus", value: "off")
            log.info "Power status: OFF"
            break

        case "01":
            sendEvent(name: "switch", value: "on")
            sendEvent(name: "powerStatus", value: "on")
            log.info "Power status: ON"
            break

        case "02":
            sendEvent(name: "powerStatus", value: "warming")
            log.info "Power status: WARMING"
            break

        case "03":
            sendEvent(name: "powerStatus", value: "cooling")
            log.info "Power status: COOLING"
            break

        default:
            sendEvent(name: "powerStatus", value: code)
            log.info "Power status desconhecido: ${code}"
            break
    }
}

def socketStatus(String status) {
    log.warn "socketStatus(): ${status}"

    if (status?.contains("receive error") || status?.contains("send error") || status?.contains("error") || status?.contains("closed")) {
        sendEvent(name: "connection", value: "disconnected")
    }
}

def connectSocket() {
    try {
        interfaces.rawSocket.close()
    } catch (e) {
    }

    try {
        if (logEnable) log.debug "Conectando em ${ipAddress}:${tcpPort}"
        interfaces.rawSocket.connect(ipAddress, tcpPort.toInteger(), byteInterface: false)
        pauseExecution(500)
        sendEvent(name: "connection", value: "connected")
        log.info "Socket conectado em ${ipAddress}:${tcpPort}"
    } catch (Exception e) {
        sendEvent(name: "connection", value: "disconnected")
        log.error "Erro ao conectar socket: ${e.message}"
    }
}

def disconnectSocket() {
    try {
        interfaces.rawSocket.close()
        sendEvent(name: "connection", value: "disconnected")
        log.info "Socket desconectado"
    } catch (Exception e) {
        log.error "Erro ao desconectar socket: ${e.message}"
    }
}

def on() {
    sendPowerOnCommand()
}

def off() {
    sendPowerOffCommand()
}

def sendPowerOnCommand() {
    sendPJLinkCommand("%1POWR 1")
    runIn(8, "refresh")
}

def sendPowerOffCommand() {
    sendPJLinkCommand("%1POWR 0")
    runIn(8, "refreshPower")
}

def refreshPower() {
    sendPJLinkCommand("%1POWR ?")
}

def refreshInput() {
    sendPJLinkCommand("%1INPT ?")
}

def refresh() {
    refreshPower()
}


def sendRawCommand(String cmd) {
    if (!cmd) return
    sendPJLinkCommand(cmd)
}

private void sendPJLinkCommand(String cmd) {
    if (!ipAddress || !tcpPort) {
        log.error "IP Address / TCP Port não configurados"
        return
    }

    try {
        try {
            interfaces.rawSocket.close()
        } catch (e) {
        }

        sendEvent(name: "connection", value: "disconnected")

        if (logEnable) log.debug "Conectando em ${ipAddress}:${tcpPort}"
        interfaces.rawSocket.connect(ipAddress, tcpPort.toInteger(), byteInterface: false)
        pauseExecution(1000)

        sendEvent(name: "connection", value: "connected")
        log.info "Socket conectado em ${ipAddress}:${tcpPort}"

        String fullCmd = "${cmd}\r"
        if (logEnable) log.debug "Enviando PJLink: ${cmd} | bytes=${fullCmd.bytes.encodeHex().toString()}"
        interfaces.rawSocket.sendMessage(fullCmd)

        runIn(2, "disconnectSocket")
    } catch (Exception e) {
        sendEvent(name: "connection", value: "disconnected")
        log.error "Erro ao enviar comando '${cmd}': ${e.message}"
    }
}

def logsOff() {
    log.warn "Debug logging desativado"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}