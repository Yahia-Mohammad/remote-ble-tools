#compdef remoteble rble

_remoteble() {
  local -a commands child_global
  commands=(
    'agent:inspect agent capabilities and gated operations'
    'scan:scan for advertisements'
    'connect:connect a device'
    'disconnect:disconnect a device'
    'inspect:discover GATT services'
    'read:read a characteristic'
    'descriptor:read a descriptor'
    'rssi:read connected-link RSSI'
    'observe:stream notifications'
    'session:run a persistent JSONL machine session'
    'shell:run the interactive human shell'
    'write:write only with enforced agent policy'
    'report:show recent local audit records'
    'config:show or validate configuration'
  )
  child_global=(
    '--config=[YAML configuration path]:path:_files'
    '--profile=[configuration profile]:profile'
    '--endpoint=[agent WebSocket endpoint]:endpoint'
    '--client-id=[client identifier]:client id'
    '--token-stdin[read the bearer token from standard input]'
    '--json[emit JSON]' '--jsonl[emit JSONL]' '--quiet[emit primary value]'
    '--output=[output mode]:mode:(human json jsonl hex base64 raw quiet)'
    '--log-level=[operational log level]:level:(audit debug)'
    '(-h --help)'{-h,--help}'[show help]'
  )
  _arguments -s \
    '--config=[YAML configuration path]:path:_files' \
    '--profile=[configuration profile]:profile' \
    '--endpoint=[agent WebSocket endpoint]:endpoint' \
    '--client-id=[client identifier]:client id' \
    '--token-stdin[read the bearer token from standard input]' \
    '--json[emit JSON]' '--jsonl[emit JSONL]' '--quiet[emit primary value]' \
    '--output=[output mode]:mode:(human json jsonl hex base64 raw quiet)' \
    '--log-level=[operational log level]:level:(audit debug)' \
    '--generate-completion=[generate a completion script]:shell:(bash fish zsh)' \
    '(-h --help)'{-h,--help}'[show help]' '(-V --version)'{-V,--version}'[show version]' \
    '1:command:->command' '*::argument:->args'
  case $state in
    command) _describe -t commands 'remoteble command' commands ;;
    args)
      case $words[2] in
        agent) _arguments $child_global '1:agent command:(capabilities status slots)' '--operator[request operator status]' ;;
        descriptor) _arguments $child_global '1:descriptor command:(read)' ;;
        scan) _arguments $child_global '--duration=[scan duration]:duration' '--service=[service UUID]:UUID' '--name=[advertised name]:name' '--minimum-rssi=[minimum RSSI]:RSSI' '--max-events=[maximum advertisements]:count' ;;
        connect|disconnect|inspect|rssi) _arguments $child_global '--name=[advertised name]:name' '--service=[service UUID]:UUID' ;;
        observe) _arguments $child_global '--count=[notification count]:count' '--timeout=[stream deadline]:duration' '--unbounded[allow an unbounded stream when policy permits]' ;;
        write) _arguments $child_global '--hex=[hexadecimal payload]:hex' '--base64=[Base64 payload]:Base64' '--text=[UTF-8 payload]:text' '--stdin=[read encoded payload from stdin]:encoding:(hex base64 text)' '--write-type=[BLE acknowledgement behavior]:type:(with-response without-response)' ;;
        report) _arguments $child_global '--limit=[maximum audit records]:count' ;;
        session|shell) _arguments $child_global '--operator[present the operator credential]' ;;
        config) _arguments $child_global '1:config command:(show validate)' ;;
      esac ;;
  esac
}

compdef _remoteble remoteble rble
