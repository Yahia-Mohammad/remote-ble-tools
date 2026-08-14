complete -c remoteble -f
complete -c rble -f

for command in agent scan connect disconnect inspect read descriptor rssi observe write report session shell config
    complete -c remoteble -n '__fish_use_subcommand' -a $command
    complete -c rble -n '__fish_use_subcommand' -a $command
end

for command in remoteble rble
    complete -c $command -n '__fish_seen_subcommand_from agent; and not __fish_seen_subcommand_from capabilities status slots' -a capabilities
    complete -c $command -n '__fish_seen_subcommand_from agent; and not __fish_seen_subcommand_from capabilities status slots' -a status
    complete -c $command -n '__fish_seen_subcommand_from agent; and not __fish_seen_subcommand_from capabilities status slots' -a slots
    complete -c $command -n '__fish_seen_subcommand_from descriptor; and not __fish_seen_subcommand_from read' -a read
    complete -c $command -n '__fish_seen_subcommand_from config; and not __fish_seen_subcommand_from show validate' -a show
    complete -c $command -n '__fish_seen_subcommand_from config; and not __fish_seen_subcommand_from show validate' -a validate
end

for option in '--token-stdin' '--json' '--jsonl' '--quiet' '--help' '--version'
    complete -c remoteble -l (string replace -r '^--' '' $option)
    complete -c rble -l (string replace -r '^--' '' $option)
end

for command in remoteble rble
    complete -c $command -l config -r -F
    complete -c $command -l profile -r
    complete -c $command -l endpoint -r
    complete -c $command -l client-id -r
    complete -c $command -l output -r -a 'human json jsonl hex base64 raw quiet'
    complete -c $command -l log-level -r -a 'audit debug'
    complete -c $command -l generate-completion -r -a 'bash fish zsh'
    complete -c $command -n '__fish_seen_subcommand_from scan' -l duration -r
    complete -c $command -n '__fish_seen_subcommand_from scan' -l service -r
    complete -c $command -n '__fish_seen_subcommand_from scan' -l name -r
    complete -c $command -n '__fish_seen_subcommand_from scan' -l minimum-rssi -r
    complete -c $command -n '__fish_seen_subcommand_from scan' -l max-events -r
    complete -c $command -n '__fish_seen_subcommand_from connect disconnect inspect rssi' -l name -r
    complete -c $command -n '__fish_seen_subcommand_from connect disconnect inspect rssi' -l service -r
    complete -c $command -n '__fish_seen_subcommand_from observe' -l count -r
    complete -c $command -n '__fish_seen_subcommand_from observe' -l timeout -r
    complete -c $command -n '__fish_seen_subcommand_from observe' -l unbounded
    complete -c $command -n '__fish_seen_subcommand_from write' -l hex -r
    complete -c $command -n '__fish_seen_subcommand_from write' -l base64 -r
    complete -c $command -n '__fish_seen_subcommand_from write' -l text -r
    complete -c $command -n '__fish_seen_subcommand_from write' -l stdin -r -a 'hex base64 text'
    complete -c $command -n '__fish_seen_subcommand_from write' -l write-type -r -a 'with-response without-response'
    complete -c $command -n '__fish_seen_subcommand_from report' -l limit -r
    complete -c $command -n '__fish_seen_subcommand_from status session shell' -l operator
end
