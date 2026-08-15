# Bash completion for remoteble and its rble alias.
_remoteble() {
    local cur prev words cword
    if declare -F _init_completion >/dev/null; then
        _init_completion -n : || return
    else
        words=( "${COMP_WORDS[@]}" )
        cword=$COMP_CWORD
        cur=${COMP_WORDS[COMP_CWORD]}
        prev=${COMP_WORDS[COMP_CWORD-1]}
    fi

    local commands='agent scan connect disconnect inspect read descriptor rssi observe write report session shell config skills'
    local global='--config --profile --endpoint --client-id --token-stdin --json --jsonl --quiet --output --log-level --generate-completion --help --version'
    local child_global='--config --profile --endpoint --client-id --token-stdin --json --jsonl --quiet --output --log-level --help'

    case "$prev" in
        --config) if declare -F _filedir >/dev/null; then _filedir yaml; else COMPREPLY=( $(compgen -f -- "$cur") ); fi; return ;;
        --output) COMPREPLY=( $(compgen -W 'human json jsonl hex base64 raw quiet' -- "$cur") ); return ;;
        --log-level) COMPREPLY=( $(compgen -W 'audit debug' -- "$cur") ); return ;;
        --generate-completion) COMPREPLY=( $(compgen -W 'bash fish zsh' -- "$cur") ); return ;;
        --write-type) COMPREPLY=( $(compgen -W 'with-response without-response' -- "$cur") ); return ;;
        --stdin) COMPREPLY=( $(compgen -W 'hex base64 text' -- "$cur") ); return ;;
        agent) COMPREPLY=( $(compgen -W 'status capabilities slots' -- "$cur") ); return ;;
        descriptor) COMPREPLY=( $(compgen -W 'read' -- "$cur") ); return ;;
        skills) COMPREPLY=( $(compgen -W 'install doctor' -- "$cur") ); return ;;
    esac

    if [[ $cword -eq 1 ]]; then
        COMPREPLY=( $(compgen -W "$commands $global" -- "$cur") )
        return
    fi

    local command=''
    local word
    for word in "${words[@]:1}"; do
        case " $commands " in *" $word "*) command=$word; break ;; esac
    done

    case "$command" in
        agent) COMPREPLY=( $(compgen -W "status capabilities slots --operator $child_global" -- "$cur") ) ;;
        scan) COMPREPLY=( $(compgen -W "--duration --service --name --minimum-rssi --max-events $child_global" -- "$cur") ) ;;
        connect|disconnect|inspect|rssi) COMPREPLY=( $(compgen -W "--name --service $child_global" -- "$cur") ) ;;
        observe) COMPREPLY=( $(compgen -W "--count --timeout --unbounded $child_global" -- "$cur") ) ;;
        write) COMPREPLY=( $(compgen -W "--hex --base64 --text --stdin --write-type $child_global" -- "$cur") ) ;;
        report) COMPREPLY=( $(compgen -W "--limit $child_global" -- "$cur") ) ;;
        session) COMPREPLY=( $(compgen -W "--operator $child_global" -- "$cur") ) ;;
        shell) COMPREPLY=( $(compgen -W "--operator $child_global" -- "$cur") ) ;;
        config) COMPREPLY=( $(compgen -W "show validate $child_global" -- "$cur") ) ;;
        descriptor) COMPREPLY=( $(compgen -W "read $child_global" -- "$cur") ) ;;
        skills) COMPREPLY=( $(compgen -W "install doctor --target --scope --project-dir --force $child_global" -- "$cur") ) ;;
        *) COMPREPLY=( $(compgen -W "$global" -- "$cur") ) ;;
    esac
}

complete -F _remoteble remoteble rble
