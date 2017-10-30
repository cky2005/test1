#!/bin/bash

kcp(){
    v2ray_kcp=",
    \"streamSettings\": {
      \"network\": \"kcp\",
      \"security\": \"none\",
      \"kcpSettings\": {
        \"mtu\": 1350,
        \"tti\": 20,
        \"uplinkCapacity\": 5,
        \"downlinkCapacity\": 20,
        \"congestion\": false,
        \"readBufferSize\": 1,
        \"writeBufferSize\": 1,
        \"header\": {
          \"type\": \"$1\"
        }
      }
    }"
  }

 kcp_h(){
  echo "-------------------------------------------------------------------"
  echo "$1数据包头部伪装:"
  echo '0."none": 默认值，不进行伪装，发送的数据是没有特征的数据包。'
  echo '1."srtp": 伪装成 SRTP 数据包，会被识别为视频通话数据（如 FaceTime）。'
  echo '2."utp": 伪装成 uTP 数据包，会被识别为 BT 下载数据。'
  echo '3."wechat-video": 伪装成微信视频通话的数据包。'
  input selecta "数字0-3" 0
  if [[ $[selecta] == "1" ]];then
    kcp_header="srtp"
  elif [[ $[selecta] == "2" ]];then
    kcp_header="utp"
  elif [[ $[selecta] == "3" ]];then
    kcp_header="wechat-video"
  else
    kcp_header="none"
  fi
}

ipip () {
  ip=$(wget -qO- -t1 -T2 ipinfo.io/ip)
  if [[ -z "${ip}" ]]; then
    ip=$(wget -qO- -t1 -T2 api.ip.sb/ip)
    if [[ -z "${ip}" ]]; then
      ip=$(wget -qO- -t1 -T2 members.3322.org/dyndns/getip)
      if [[ -z "${ip}" ]]; then
        ip="VPS_IP"
      fi
    fi
  fi
}
#ip="123.456.789.012"
input () {
  read -p "请输入$2,默认($3):" $1
  eval ${1}=${!1:-$3}
}
outputs(){
cat > ./$1.json <<EOF
  $v2ray_log
  $v2ray_inbound
  $v2ray_inboundDetour
  $v2ray_outbound
  $v2ray_outboundDetour
  $v2ray_transport
  $v2ray_dns$v2ray_routing
EOF
}
outputc(){
cat > ./$1.json <<EOF
  $client_log
  $client_inbound
  $client_outbound
  $client_outboundDetour
  $client_dns
  $client_transport
  $client_routing
EOF
}

input v2ray_port 端口 10086

[ -f "/proc/sys/kernel/random/uuid" ] && UUID=$(cat /proc/sys/kernel/random/uuid)
[ -f "/f/temp/uuid" ] && UUID=$(cat /f/temp/uuid)
[ -z "${UUID}" ] && UUID="173fa4bb-b916-4943-b8d4-e8e37529a9f6"
input v2ray_uuid UUID ${UUID}

echo "---------------------------------"
echo "0.普通最简设置(默认)"
echo "1.设置websocket路径转发(需要http服务器支持)"
echo "2.设置http头伪装"
echo "3.设置域名tls伪装(需要服务器绑定域名和域名证书)"
echo "4.设置kcp"
input select "数字0-4" 0
if [[ "${select}" == "1" ]];then
  #设置 websocket 路径,使用 HTTP 服务器（如 NGINX / caddy /apahe）分流.
  input v2ray_url "域名" "-"
  [[ -z "${v2ray_url}" ]] && echo "没有输入域名" && exit 1
  input v2ray_wspath "Websocket 路径" ws
  [ -z "${v2ray_wspath}" ] && v2ray_wspath="ws"
  c_on=1
  v2ray_ws=",
    \"streamSettings\": {
      \"network\": \"ws\",
      \"wsSettings\": {
        \"connectionReuse\": false,
        \"path\": \"/${v2ray_wspath}\"
      }
    }"
elif [[ "${select}" == "2" ]];then
  #使用 http headers 伪装
  c_on=2
  httpheaders=',
    "streamSettings": {
      "network": "tcp",
      "tcpSettings": {
        "connectionReuse": true,
        "header": {
          "type": "http",
          "request": {
            "version": "1.1",
            "method": "GET",
            "path": ["/"],
            "headers": {
              "Host": ["www.baidu.com", "www.bing.com"],
              "User-Agent": [
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.75 Safari/537.36",
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 10_0_2 like Mac OS X) AppleWebKit/601.1 (KHTML, like Gecko) CriOS/53.0.2785.109 Mobile/14A456 Safari/601.1.46"
              ],
              "Accept-Encoding": ["gzip, deflate"],
              "Connection": ["keep-alive"],
              "Pragma": "no-cache"
            }
          },
          "response": {
            "version": "1.1",
            "status": "200",
            "reason": "OK",
            "headers": {
              "Content-Type": ["application/octet-stream", "application/x-msdownload", "text/html", "application/x-shockwave-flash"],
              "Transfer-Encoding": ["chunked"],
              "Connection": ["keep-alive"],
              "Pragma": "no-cache"
            }
          }
        }
      }
    }'

elif [[ "${select}" == "3" ]];then
  # 使用 域名tls证书 伪装 ()
  c_on=3
  input v2ray_tls "你的域名(如abc.com)" "无"
  [ -z ${v2ray_tls} ] && echo "没有输入域名,操作中断" && exit 1
  echo "---------------------------------------------------------"
  echo "请输入证书路径,默认将会调用输入路径里面的v2ray.crt和v2ray.key"
  input v2ray_key "证书目录路径" "/etc/v2ray/"
  [ -z ${v2ray_key} ] && v2ray_key="/etc/v2ray/"
  [ ! -f "${v2ray_key}v2ray.crt" ] && echo "没有找到${v2ray_key}v2ray.crt" && exit 1
  [ ! -f "${v2ray_key}v2ray.key" ] && echo "没有找到${v2ray_key}v2ray.key" && exit 1
  certificates=",
    \"streamSettings\": {
      \"network\": \"tcp\",
      \"security\": \"none\",
      \"tlsSettings\": {
        \"serverName\": \"${v2ray_tls}\",
        \"allowInsecure\": false,
        \"certificates\": [
          {
            \"certificateFile\": \"${v2ray_key}v2ray.crt\",
            \"keyFile\": \"${v2ray_key}v2ray.key\"
          }
        ]
      },
      \"tcpSettings\": {},
      \"kcpSettings\": {},
      \"wsSettings\": {}
    }"
elif [[ ${select} == "4" ]];then
  kcp_h
  c_on=8
  kcp ${kcp_header}
else
  c_on=9
  echo "选择了不设置"
fi  

v2ray_log='
{
  "log" : {
    "access": "/var/log/v2ray/access.log",
    "error": "/var/log/v2ray/error.log",
    "loglevel": "warning"
  },'

v2ray_inbound="
  \"inbound\": {
    \"port\": ${v2ray_port},
    \"protocol\": \"vmess\",
    \"settings\": {
      \"clients\": [
        {
          \"id\": \"${v2ray_uuid}\",
          \"level\": 1,
          \"alterId\": 64
        }
      ]
    }${v2ray_ws}${httpheaders}${certificates}${v2ray_kcp}
  },"

add_s=0
echo "--------------------"
echo "0.跳过"
echo "1.增加一个kcp动态端口"
input select "数字0-1" 0
if [[ ${select} == "1" ]];then
  kcp_h "kcp动态端口"
  dkcp_on=1
  add_s=`expr ${add_s} + 1`
  input d_kcp_port "动态kcp端口" 28001
  input d_kcp_uuid "动态kcpUUID" "23ad6b10-8d1a-40f7-8ad0-e3e35cd38297"
  input d_kcp_dport "动态端口监听范围" "50001-50100"
  detour_inboundDetour="
    {
      \"port\": ${d_kcp_port},
      \"protocol\": \"vmess\",
      \"settings\": {
        \"clients\": [
          {
            \"id\": \"${d_kcp_uuid}\",
            \"level\": 1,
            \"alterId\": 100
          }
        ],
        \"detour\": {
          \"to\": \"detour-kcp\"
        }
      },
      \"streamSettings\": {
        \"network\": \"kcp\"
      }
    },
    {
      \"protocol\": \"vmess\",
      \"port\": \"${d_kcp_dport}\",
      \"tag\": \"detour-kcp\",
      \"settings\": {},
      \"allocate\": {
        \"strategy\": \"random\",
        \"concurrency\": 2,
        \"refresh\": 5
      },
      \"streamSettings\": {
        \"network\": \"kcp\"
      }
    }"

v2ray_transport="
  \"transport\": {
    \"kcpSettings\": {
      \"mtu\": 1350,
      \"tti\": 20,
      \"uplinkCapacity\": 12,
      \"downlinkCapacity\": 100,
      \"congestion\": false,
      \"readBufferSize\": 1,
      \"writeBufferSize\": 1,
      \"header\": {
        \"type\": \"${kcp_header}\"
      }
    }
  },"
  fi

echo "------------------------"
echo "是否创建一个shadowsocks(ss)协议:"
input v2ray_ss "(0不,1创建)" 0
  if [[ "${v2ray_ss}" == "1" ]];then
  add_s=`expr ${add_s} + 1`
  input ss_port ss端口 8080
  echo "-------------"
  echo "1.aes-256-cfb"
  echo "2.aes-128-cfb"
  echo "3.chacha20(默认)"
  echo "4.chacha20-ietf"
  input method 加密协议 chacha20
  if [[ ${method} == "1" ]];then
    ss_method="aes-256-cfb"
  elif [[ ${method} == "2" ]];then
    ss_method="aes-128-cfb"
  elif [[ ${method} == "3" ]];then
    ss_method="chacha20"
  elif [[ ${method} == "4" ]];then
    ss_method="chacha20-ietf"
  else
    ss_method="chacha20"
  fi
  input ss_password ss密码 1234567
  echo "端口: ${ss_port}"
  echo "协议: ${ss_method}"
  echo "密码: ${ss_password}"
  #input s_udp "udp(0关闭,1开启)" 0
  #[ -z ${s_udp} ] && ss_udp="false" || ss_udp="true"
  ss_inboundDetour="
    {
      \"protocol\": \"shadowsocks\",
      \"port\": ${ss_port}, 
      \"settings\": {
        \"method\": \"${ss_method}\",
        \"password\": \"${ss_password}\",     
        \"udp\": false,
        \"ota\": false,
        \"email\": \"${ss_port}@abc.com\"
      }
    }"
    [[ ${add_s} == "2" ]] && addb=","
fi

v2ray_inboundDetour="
  \"inboundDetour\": [${ss_inboundDetour}${addb}${detour_inboundDetour}
  ],"

v2ray_outbound='
  "outbound": {
    "protocol": "freedom",
    "settings": {}
  },'

v2ray_outboundDetour='
  "outboundDetour": [
    {
      "protocol": "blackhole",
      "settings": {},
      "tag": "blocked"
    }
  ],'

off_v2ray_dns='
  "dns": {
    "hosts": {
      "baidu.com": "127.0.0.1"
    },
    "servers": [
      "8.8.8.8",
      "8.8.4.4",
      "localhost"
    ]
  },'

v2ray_routing='
  "routing": {
    "strategy": "rules",
    "settings": {
      "rules": [
        {
          "type": "field",
          "ip": [
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.0.0.0/24",
            "192.0.2.0/24",
            "192.168.0.0/16",
            "198.18.0.0/15",
            "198.51.100.0/24",
            "203.0.113.0/24",
            "::1/128",
            "fc00::/7",
            "fe80::/10"
          ],
          "outboundTag": "blocked"
        }
      ]
    }
  }
}'
outputs v2

client_log='
{
  "log": {
    "access": "",
    "error": "",
    "loglevel": ""
  },'

client_inbound='
  "inbound": {
    "port": 1080,
    "listen": "127.0.0.1",
    "protocol": "socks",
    "settings": {
      "auth": "noauth",
      "udp": false,
      "ip": "127.0.0.1"
    }
  },'

client_inboundDetour='
  "inboundDetour": null,'

client_outboundDetour='
  "outboundDetour": [
    {
      "protocol": "freedom",
      "settings": {},
      "tag": "direct"
    },
    {
      "protocol": "blackhole",
      "settings": {
        "response": {
          "type": "http"
        }
      },
      "tag": "blockout"
    }
  ],'

 client_dns='
  "dns": {
    "servers": [
      "8.8.8.8",
      "8.8.4.4",
      "localhost"
    ]
  },'

client_routing='
  "routing": {
    "strategy": "rules",
    "settings": {
      "domainStrategy": "IPIfNonMatch",
      "rules": [
        {
          "type": "field",
          "port": null,
          "outboundTag": "direct",
          "ip": [
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.0.0.0/24",
            "192.0.2.0/24",
            "192.168.0.0/16",
            "198.18.0.0/15",
            "198.51.100.0/24",
            "203.0.113.0/24",
            "::1/128",
            "fc00::/7",
            "fe80::/10"
          ],
          "domain": null
        },
        {
          "type": "chinasites",
          "port": null,
          "outboundTag": "direct",
          "ip": null,
          "domain": null
        },
        {
          "type": "chinaip",
          "port": null,
          "outboundTag": "direct",
          "ip": null,
          "domain": null
        }
      ]
    }
  }
}'

echo "---------------------"
echo "生成客户端配置文件:"
echo "0.跳过"
echo "1.生成(默认)"
input select "数字0-1" 1
[[ "${select}" == "0" ]] && exit 1
if [[ "${select}" == "1" && "${c_on}" == "100" ]];then
  client_outbound="
  \"outbound\": {
    \"protocol\": \"shadowsocks\",
    \"settings\": {
      \"servers\": [
        {
          \"address\": \"${ip}\",
          \"port\": ${ss_port},
          \"method\": \"${ss_method}\",
          \"password\": \"${ss_password}\",
          \"ota\": false,
          \"email\": \"${ss_port}@abc.com\"
        }
      ]
    }
  },"
outputc v2-ss
echo "-----------------"
echo "ss客户端配置(v2-ss.json):"
echo "ip  :${ip}"
echo "端口:${ss_port}"
echo "协议:${ss_method}"
echo "密码:${ss_password}"
fi

[[ -n ${v2ray_tls} ]] && ip=${v2ray_tls}
  c_outbound="
  \"outbound\": {
    \"tag\": \"agentout\",
    \"protocol\": \"vmess\",
    \"settings\": {
      \"vnext\": [
        {
          \"address\": \"${ip}\",
          \"port\": ${v2ray_port},
          \"users\": [
            {
              \"id\": \"d${UUID}\",
              \"alterId\": 64,
              \"security\": \"aes-128-gcm\"
            }
          ]
        }
      ]
    }"

    c_mux="
    \"mux\": {
      \"enabled\": true
    }
  },"

if [[ "${select}" == "1" ]];then
  if [[ "${c_on}" == "1" ]];then
  client_outbound="
  \"outbound\": {
    \"tag\": \"wsout\",
    \"protocol\": \"vmess\",
    \"settings\": {
      \"vnext\": [
        {
          \"address\": \"${v2ray_url}\",
          \"port\": 443,
          \"users\": [
            {
              \"id\": \"${UUID}\",
              \"alterId\": 64,
              \"security\": \"aes-128-gcm\"
            }
          ]
        }
      ]
    },
    \"streamSettings\": {
      \"network\": \"ws\",
      \"security\": \"tls\",
      \"tlsSettings\": {
        \"serverName\": \"${v2ray_url}\",
        \"allowInsecure\": true
    },
      \"wsSettings\": {
        \"connectionReuse\": true,
        \"path\": \"/${v2ray_wspath}/\"
      }
    },${c_mux}"
  outputc v2-ws
  echo "-----------------"
  echo "ws客户端配置(v2-ws.json):"
  echo "域名/ip   : ${v2ray_url}"
  echo "端口      : 443"
  echo "ＩＤ      : ${UUID}"
  echo "ws路径    : /${v2ray_wspath}/"
  echo "ＴＬＳ    : 开启"
  echo "alterＩＤ : 64"
  exit 0

  elif [[ "${c_on}" == "2" ]]; then
  client_outbound="
   ${c_outbound}${httpheaders},${c_mux}"
   outputc v2-kehu

  elif [[ "${c_on}" == "3" ]]; then
   client_outbound="
    ${c_outbound},
      \"streamSettings\": {
      \"network\": \"tcp\",
      \"security\": \"none\",
      \"tlsSettings\": {
        \"serverName\": \"${v2ray_tls}\",
        \"allowInsecure\": false,
      },
      \"tcpSettings\": {},
      \"kcpSettings\": {},
      \"wsSettings\": {}
    },${c_mux}"
   outputc v2-kehu

  elif [[ "${c_on}" == "8" ]]; then
   client_outbound="${c_outbound}${v2ray_kcp},${c_mux}"
   outputc v2-kehu 

  elif [[ "${c_on}" == "9" ]]; then
  client_outbound="${c_outbound},
    \"streamSettings\": {
      \"network\": \"tcp\",
      \"security\": \"\",
      \"tcpSettings\": null,
      \"kcpSettings\": null,
      \"wsSettings\": null
    },${c_mux}"
  outputc v2-kehu
  fi  

if [[ "${dkcp_on}" == "1" ]]; then
  kcp ${kcp_header}
  client_outbound="
  \"outbound\": {
    \"tag\": \"agentout\",
    \"protocol\": \"vmess\",
    \"settings\": {
      \"vnext\": [
        {
          \"address\": \"${ip}\",
          \"port\": ${d_kcp_port},
          \"users\": [
            {
              \"id\": \"d${d_kcp_uuid}\",
              \"alterId\": 100,
              \"security\": \"aes-128-gcm\"
            }
          ]
        }
      ]
    }${v2ray_kcp},${c_mux}"
#  client_transport="${v2ray_transport}"
  echo "动态kcp端口客户端配置(v2-dkcp.json)"
  outputc v2-dkcp
fi
echo "-----------------"
echo "服务端配置文件(v2.json)"
echo "客户端配置(v2-kehu.json):"
echo "域名/ip   : ${ip}"
echo "Ｎetwork  : ${c_on} (1=ws-path,2=http伪装,3=tls,8=kcp,9=tcp)"
fi

