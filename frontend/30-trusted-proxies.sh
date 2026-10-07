#!/bin/sh
# V10.5: real client addresses behind a cloud load balancer.
#
# nginx passes $remote_addr to the backend as X-Forwarded-For, and the backend rate-limits by it.
# Behind a load balancer $remote_addr is the balancer, so every user would share one limit. When
# JMIP_TRUSTED_PROXIES lists the balancer's addresses (comma-separated CIDRs or IPs), nginx takes the
# client address from X-Forwarded-For, but only for connections from those addresses; anyone else
# still cannot choose their own address. Empty (the default): nothing is trusted.
set -eu

CONF=/etc/nginx/conf.d/00-trusted-proxies.conf
rm -f "$CONF"

[ -z "${JMIP_TRUSTED_PROXIES:-}" ] && exit 0

{
  echo "# Generated from JMIP_TRUSTED_PROXIES at container start."
  for proxy in $(echo "$JMIP_TRUSTED_PROXIES" | tr ',' ' '); do
    case "$proxy" in
      *[!0-9a-fA-F.:/]*|"")
        echo "30-trusted-proxies.sh: JMIP_TRUSTED_PROXIES has an invalid entry; use IPs or CIDRs" >&2
        exit 1 ;;
    esac
    echo "set_real_ip_from $proxy;"
  done
  echo "real_ip_header X-Forwarded-For;"
  echo "real_ip_recursive on;"
} > "$CONF"
echo "30-trusted-proxies.sh: trusting X-Forwarded-For from the configured load balancer addresses"
