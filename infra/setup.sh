#!/usr/bin/env bash
# =============================================================================
# setup.sh — Full-stack backend + ML infrastructure installer
# Supports: Ubuntu 22.04 (Jammy) / 24.04 (Noble) / Debian 12 (Bookworm)
# Usage:    ./setup.sh [--flag ...] | --all | (no args → interactive)
# =============================================================================
set -euo pipefail

# ─── Pinned versions (update here when upgrading) ────────────────────────────
KAFKA_VERSION="3.9.0"
KAFKA_SCALA="2.13"
SPARK_VERSION="3.5.4"
SPARK_HADOOP="3"
AIRFLOW_VERSION="2.10.5"
POSTGRES_VERSION="16"

# ─── Color helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_success() { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
section()     { echo -e "\n${BOLD}${CYAN}══ $* ══${NC}"; }

# ─── Component flags (all default false) ─────────────────────────────────────
INSTALL_POSTGRES=false
INSTALL_CASSANDRA=false
INSTALL_REDIS=false
INSTALL_MONGODB=false
INSTALL_CLICKHOUSE=false
INSTALL_ELASTICSEARCH=false   # includes Kibana
INSTALL_KAFKA=false
INSTALL_RABBITMQ=false
INSTALL_MEMCACHED=false
INSTALL_MINIO=false
INSTALL_NGINX=false
INSTALL_HAPROXY=false
INSTALL_ENVOY=false
INSTALL_KONG=false
INSTALL_PROMETHEUS=false
INSTALL_GRAFANA=false
INSTALL_JAEGER=false
INSTALL_OTELCOL=false
INSTALL_JAVA=false
INSTALL_GO=false
INSTALL_NODE=false
INSTALL_RUST=false
INSTALL_QDRANT=false
INSTALL_OLLAMA=false
INSTALL_SPARK=false
INSTALL_CUDA=false
INSTALL_AI_STACK=false
INSTALL_BAZEL=false

# ─── Usage ────────────────────────────────────────────────────────────────────
usage() {
  cat <<EOF
${BOLD}setup.sh${NC} — Backend + ML infrastructure installer for Debian/Ubuntu

${BOLD}Usage:${NC}
  ./setup.sh [OPTIONS]
  ./setup.sh --all
  ./setup.sh              (interactive checklist)

${BOLD}Backend flags:${NC}
  --postgres        PostgreSQL ${POSTGRES_VERSION} + pgvector + Citus   :5432
  --cassandra       Apache Cassandra 4.1              :9042
  --redis           Redis (latest)                    :6379
  --mongodb         MongoDB 7.x                       :27017
  --clickhouse      ClickHouse                        :9000,8123
  --elasticsearch   Elasticsearch 8 + Kibana          :9200,5601
  --kafka           Apache Kafka ${KAFKA_VERSION} (KRaft)          :9092
  --rabbitmq        RabbitMQ                          :5672
  --memcached       Memcached                         :11211
  --minio           MinIO object storage              :9000,9001
  --nginx           Nginx reverse proxy               :80,443
  --haproxy         HAProxy load balancer             :80,8404
  --envoy           Envoy proxy                       :varies
  --kong            Kong API Gateway OSS              :8000,8001
  --prometheus      Prometheus                        :9090
  --grafana         Grafana OSS                       :3000
  --jaeger          Jaeger all-in-one                 :16686
  --otelcol         OpenTelemetry Collector           :4317,4318

${BOLD}Dev toolchain flags:${NC}
  --java            OpenJDK 21 + Maven
  --go              Go (latest)
  --node            Node.js 20 LTS
  --rust            Rust via rustup
  --bazel           Bazel build system

${BOLD}ML infra flags:${NC}
  --qdrant          Qdrant vector database            :6333
  --ollama          Ollama LLM server                 :11434
  --spark           Apache Spark ${SPARK_VERSION}             :7077,8080
  --cuda            NVIDIA CUDA toolkit (GPU required)
  --ai-stack        Full Python ML env in conda 'dev'

  --all             Install everything above
  --help            Show this help

${BOLD}Examples:${NC}
  ./setup.sh --postgres --redis --nginx --ai-stack
  ./setup.sh --all
  ./setup.sh                        # interactive menu
EOF
  exit 0
}

# ─── Parse arguments ─────────────────────────────────────────────────────────
_any_flag=false
parse_args() {
  [[ $# -eq 0 ]] && return
  for arg in "$@"; do
    case "$arg" in
      --help|-h)         usage ;;
      --all)
        for v in POSTGRES CASSANDRA REDIS MONGODB CLICKHOUSE ELASTICSEARCH \
                 KAFKA RABBITMQ MEMCACHED MINIO NGINX HAPROXY ENVOY KONG \
                 PROMETHEUS GRAFANA JAEGER OTELCOL JAVA GO NODE RUST BAZEL \
                 QDRANT OLLAMA SPARK CUDA AI_STACK; do
          eval "INSTALL_${v}=true"
        done
        _any_flag=true ;;
      --postgres)        INSTALL_POSTGRES=true;        _any_flag=true ;;
      --cassandra)       INSTALL_CASSANDRA=true;       _any_flag=true ;;
      --redis)           INSTALL_REDIS=true;           _any_flag=true ;;
      --mongodb)         INSTALL_MONGODB=true;         _any_flag=true ;;
      --clickhouse)      INSTALL_CLICKHOUSE=true;      _any_flag=true ;;
      --elasticsearch)   INSTALL_ELASTICSEARCH=true;   _any_flag=true ;;
      --kafka)           INSTALL_KAFKA=true;           _any_flag=true ;;
      --rabbitmq)        INSTALL_RABBITMQ=true;        _any_flag=true ;;
      --memcached)       INSTALL_MEMCACHED=true;       _any_flag=true ;;
      --minio)           INSTALL_MINIO=true;           _any_flag=true ;;
      --nginx)           INSTALL_NGINX=true;           _any_flag=true ;;
      --haproxy)         INSTALL_HAPROXY=true;         _any_flag=true ;;
      --envoy)           INSTALL_ENVOY=true;           _any_flag=true ;;
      --kong)            INSTALL_KONG=true;            _any_flag=true ;;
      --prometheus)      INSTALL_PROMETHEUS=true;      _any_flag=true ;;
      --grafana)         INSTALL_GRAFANA=true;         _any_flag=true ;;
      --jaeger)          INSTALL_JAEGER=true;          _any_flag=true ;;
      --otelcol)         INSTALL_OTELCOL=true;         _any_flag=true ;;
      --java)            INSTALL_JAVA=true;            _any_flag=true ;;
      --go)              INSTALL_GO=true;              _any_flag=true ;;
      --node)            INSTALL_NODE=true;            _any_flag=true ;;
      --rust)            INSTALL_RUST=true;            _any_flag=true ;;
      --bazel)           INSTALL_BAZEL=true;           _any_flag=true ;;
      --qdrant)          INSTALL_QDRANT=true;          _any_flag=true ;;
      --ollama)          INSTALL_OLLAMA=true;          _any_flag=true ;;
      --spark)           INSTALL_SPARK=true;           _any_flag=true ;;
      --cuda)            INSTALL_CUDA=true;            _any_flag=true ;;
      --ai-stack)        INSTALL_AI_STACK=true;        _any_flag=true ;;
      *) log_error "Unknown flag: $arg"; usage ;;
    esac
  done
}

# ─── Interactive selection (whiptail or plain prompts) ───────────────────────
interactive_select() {
  section "Component Selection"
  if command -v whiptail &>/dev/null; then
    _whiptail_select
  else
    _prompt_select
  fi
}

_whiptail_select() {
  local choices
  choices=$(whiptail --title "Stack Setup" \
    --checklist "Space to select, Enter to confirm:" 35 65 28 \
    "postgres"       "PostgreSQL ${POSTGRES_VERSION} + pgvector + Citus  :5432"    OFF \
    "cassandra"      "Cassandra 4.1               :9042"    OFF \
    "redis"          "Redis                        :6379"    OFF \
    "mongodb"        "MongoDB 7.x                 :27017"   OFF \
    "clickhouse"     "ClickHouse                  :9000"    OFF \
    "elasticsearch"  "Elasticsearch 8 + Kibana    :9200"    OFF \
    "kafka"          "Kafka ${KAFKA_VERSION} (KRaft)        :9092"    OFF \
    "rabbitmq"       "RabbitMQ                    :5672"    OFF \
    "memcached"      "Memcached                   :11211"   OFF \
    "minio"          "MinIO object storage        :9000"    OFF \
    "nginx"          "Nginx reverse proxy         :80"      OFF \
    "haproxy"        "HAProxy load balancer       :80"      OFF \
    "envoy"          "Envoy proxy"                           OFF \
    "kong"           "Kong API Gateway            :8000"    OFF \
    "prometheus"     "Prometheus                  :9090"    OFF \
    "grafana"        "Grafana OSS                 :3000"    OFF \
    "jaeger"         "Jaeger all-in-one           :16686"   OFF \
    "otelcol"        "OTel Collector              :4317"    OFF \
    "java"           "OpenJDK 21 + Maven"                   OFF \
    "go"             "Go (latest)"                          OFF \
    "node"           "Node.js 20 LTS"                       OFF \
    "rust"           "Rust (rustup)"                        OFF \
    "bazel"          "Bazel build system"                   OFF \
    "qdrant"         "Qdrant vector DB            :6333"    OFF \
    "ollama"         "Ollama LLM server           :11434"   OFF \
    "spark"          "Apache Spark ${SPARK_VERSION}        :8080"    OFF \
    "cuda"           "CUDA toolkit (GPU required)"          OFF \
    "ai-stack"       "Full Python ML stack (conda dev)"     OFF \
    3>&1 1>&2 2>&3) || { log_warn "Selection cancelled."; exit 0; }

  for item in $choices; do
    item="${item//\"/}"
    case "$item" in
      postgres)      INSTALL_POSTGRES=true ;;
      cassandra)     INSTALL_CASSANDRA=true ;;
      redis)         INSTALL_REDIS=true ;;
      mongodb)       INSTALL_MONGODB=true ;;
      clickhouse)    INSTALL_CLICKHOUSE=true ;;
      elasticsearch) INSTALL_ELASTICSEARCH=true ;;
      kafka)         INSTALL_KAFKA=true ;;
      rabbitmq)      INSTALL_RABBITMQ=true ;;
      memcached)     INSTALL_MEMCACHED=true ;;
      minio)         INSTALL_MINIO=true ;;
      nginx)         INSTALL_NGINX=true ;;
      haproxy)       INSTALL_HAPROXY=true ;;
      envoy)         INSTALL_ENVOY=true ;;
      kong)          INSTALL_KONG=true ;;
      prometheus)    INSTALL_PROMETHEUS=true ;;
      grafana)       INSTALL_GRAFANA=true ;;
      jaeger)        INSTALL_JAEGER=true ;;
      otelcol)       INSTALL_OTELCOL=true ;;
      java)          INSTALL_JAVA=true ;;
      go)            INSTALL_GO=true ;;
      node)          INSTALL_NODE=true ;;
      rust)          INSTALL_RUST=true ;;
      bazel)         INSTALL_BAZEL=true ;;
      qdrant)        INSTALL_QDRANT=true ;;
      ollama)        INSTALL_OLLAMA=true ;;
      spark)         INSTALL_SPARK=true ;;
      cuda)          INSTALL_CUDA=true ;;
      ai-stack)      INSTALL_AI_STACK=true ;;
    esac
  done
}

_prompt_select() {
  _ask() {
    local prompt="$1" var="$2"
    read -rp "  Install ${prompt}? [y/N] " ans
    [[ "${ans,,}" == "y" ]] && eval "$var=true"
  }
  echo "  (press y/Enter for each)"
  _ask "PostgreSQL ${POSTGRES_VERSION} + pgvector"     INSTALL_POSTGRES
  _ask "Cassandra 4.1"                                  INSTALL_CASSANDRA
  _ask "Redis"                                          INSTALL_REDIS
  _ask "MongoDB 7.x"                                    INSTALL_MONGODB
  _ask "ClickHouse"                                     INSTALL_CLICKHOUSE
  _ask "Elasticsearch 8 + Kibana"                       INSTALL_ELASTICSEARCH
  _ask "Kafka ${KAFKA_VERSION} (KRaft)"                 INSTALL_KAFKA
  _ask "RabbitMQ"                                       INSTALL_RABBITMQ
  _ask "Memcached"                                      INSTALL_MEMCACHED
  _ask "MinIO"                                          INSTALL_MINIO
  _ask "Nginx"                                          INSTALL_NGINX
  _ask "HAProxy"                                        INSTALL_HAPROXY
  _ask "Envoy proxy"                                    INSTALL_ENVOY
  _ask "Kong API Gateway"                               INSTALL_KONG
  _ask "Prometheus"                                     INSTALL_PROMETHEUS
  _ask "Grafana OSS"                                    INSTALL_GRAFANA
  _ask "Jaeger all-in-one"                              INSTALL_JAEGER
  _ask "OpenTelemetry Collector"                        INSTALL_OTELCOL
  _ask "OpenJDK 21 + Maven"                             INSTALL_JAVA
  _ask "Go (latest)"                                    INSTALL_GO
  _ask "Node.js 20 LTS"                                 INSTALL_NODE
  _ask "Rust (rustup)"                                  INSTALL_RUST
  _ask "Bazel build system"                             INSTALL_BAZEL
  _ask "Qdrant vector DB"                               INSTALL_QDRANT
  _ask "Ollama LLM server"                              INSTALL_OLLAMA
  _ask "Apache Spark ${SPARK_VERSION}"                  INSTALL_SPARK
  _ask "CUDA toolkit (requires NVIDIA GPU)"             INSTALL_CUDA
  _ask "AI Python stack (conda dev env)"                INSTALL_AI_STACK
}

# ─── OS detection ─────────────────────────────────────────────────────────────
DISTRO_ID=""
DISTRO_CODENAME=""

detect_os() {
  if ! command -v lsb_release &>/dev/null; then
    sudo apt-get install -y -qq lsb-release &>/dev/null
  fi
  DISTRO_ID=$(lsb_release -si | tr '[:upper:]' '[:lower:]')
  DISTRO_CODENAME=$(lsb_release -cs)
  if [[ "$DISTRO_ID" != "ubuntu" && "$DISTRO_ID" != "debian" ]]; then
    log_error "Unsupported OS: $DISTRO_ID. Only Ubuntu/Debian supported."
    exit 1
  fi
  log_info "Detected: $DISTRO_ID $DISTRO_CODENAME"
}

# ─── Sudo check ───────────────────────────────────────────────────────────────
check_sudo() {
  if [[ "$EUID" -ne 0 ]] && ! sudo -v 2>/dev/null; then
    log_error "sudo access required. Run as root or configure sudo."
    exit 1
  fi
}

# ─── Helpers ──────────────────────────────────────────────────────────────────
_keyring_dir="/etc/apt/keyrings"

add_apt_key() {
  # add_apt_key <name> <key_url>
  local name="$1" url="$2"
  sudo mkdir -p "$_keyring_dir"
  curl -fsSL "$url" | gpg --dearmor | sudo tee "${_keyring_dir}/${name}.gpg" >/dev/null
}

add_apt_source() {
  # add_apt_source <name> <line>
  local name="$1" line="$2"
  echo "$line" | sudo tee "/etc/apt/sources.list.d/${name}.list" >/dev/null
}

get_latest_github_release() {
  # get_latest_github_release <owner/repo>  → tag name (e.g. v2.1.0)
  local repo="$1"
  curl -fsSL "https://api.github.com/repos/${repo}/releases/latest" \
    | grep '"tag_name"' | head -1 | cut -d'"' -f4
}

install_binary() {
  # install_binary <url> <dest>
  local url="$1" dest="$2"
  sudo curl -fsSL "$url" -o "$dest"
  sudo chmod +x "$dest"
}

write_systemd_unit() {
  # write_systemd_unit <name> <description> <user> <exec_start> [<working_dir>]
  local name="$1" desc="$2" user="$3" exec="$4" wdir="${5:-/}"
  sudo tee "/etc/systemd/system/${name}.service" >/dev/null <<EOF
[Unit]
Description=${desc}
After=network.target

[Service]
Type=simple
User=${user}
ExecStart=${exec}
WorkingDirectory=${wdir}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
}

_gpu_present() {
  command -v nvidia-smi &>/dev/null && nvidia-smi &>/dev/null 2>&1
}

# ─── Baseline apt packages ────────────────────────────────────────────────────
_repos_added=false

install_baseline() {
  section "Baseline packages"
  sudo apt-get update -qq
  sudo apt-get install -y -qq \
    curl wget gnupg ca-certificates lsb-release \
    apt-transport-https software-properties-common \
    unzip tar gzip whiptail
  sudo mkdir -p "$_keyring_dir"
  log_success "Baseline packages installed"
}

# ─── Repository setup (call before apt-get update) ───────────────────────────

_setup_repo_pgdg() {
  [[ -f /etc/apt/sources.list.d/pgdg.list ]] && return
  add_apt_key pgdg "https://www.postgresql.org/media/keys/ACCC4CF8.asc"
  add_apt_source pgdg \
    "deb [signed-by=${_keyring_dir}/pgdg.gpg] https://apt.postgresql.org/pub/repos/apt ${DISTRO_CODENAME}-pgdg main"
}

_setup_repo_cassandra() {
  [[ -f /etc/apt/sources.list.d/cassandra.list ]] && return
  add_apt_key cassandra "https://downloads.apache.org/cassandra/KEYS"
  add_apt_source cassandra \
    "deb [signed-by=${_keyring_dir}/cassandra.gpg] https://downloads.apache.org/cassandra/debian 41x main"
}

_setup_repo_redis() {
  [[ -f /etc/apt/sources.list.d/redis.list ]] && return
  add_apt_key redis "https://packages.redis.io/gpg"
  add_apt_source redis \
    "deb [signed-by=${_keyring_dir}/redis.gpg] https://packages.redis.io/deb ${DISTRO_CODENAME} main"
}

_setup_repo_mongodb() {
  local list_file="/etc/apt/sources.list.d/mongodb-org-7.0.list"
  # Clean up existing list file if it's there
  if [[ -f "$list_file" ]]; then
    sudo rm -f "$list_file"
  fi
  # MongoDB 7.0 does NOT have a native 'noble' repo; fallback to 'jammy'
  local codename="$DISTRO_CODENAME"
  if [[ "$codename" == "noble" ]]; then
    codename="jammy"
    log_info "Ubuntu 24.04 (noble) detected for MongoDB 7.0; using jammy repository"
  fi
  [[ "$codename" != "jammy" && "$codename" != "focal" ]] && codename="jammy"
  add_apt_key mongodb-7 "https://pgp.mongodb.com/server-7.0.asc"
  add_apt_source mongodb-org-7.0 \
    "deb [arch=amd64,arm64 signed-by=${_keyring_dir}/mongodb-7.gpg] https://repo.mongodb.org/apt/ubuntu ${codename}/mongodb-org/7.0 multiverse"
}

_setup_repo_clickhouse() {
  [[ -f /etc/apt/sources.list.d/clickhouse.list ]] && return
  add_apt_key clickhouse "https://packages.clickhouse.com/rpm/lts/repodata/repomd.xml.key"
  add_apt_source clickhouse \
    "deb [arch=amd64,arm64 signed-by=${_keyring_dir}/clickhouse.gpg] https://packages.clickhouse.com/deb stable main"
}

_setup_repo_elastic() {
  [[ -f /etc/apt/sources.list.d/elastic-8.x.list ]] && return
  add_apt_key elastic "https://artifacts.elastic.co/GPG-KEY-elasticsearch"
  add_apt_source elastic-8.x \
    "deb [signed-by=${_keyring_dir}/elastic.gpg] https://artifacts.elastic.co/packages/8.x/apt stable main"
}

_setup_repo_rabbitmq() {
  [[ -f /etc/apt/sources.list.d/rabbitmq.list ]] && return
  add_apt_key rabbitmq "https://keys.openpgp.org/vks/v1/by-fingerprint/0A9AF2115F4687BD29803A206B73A36E6026DFCA"
  local codename="$DISTRO_CODENAME"
  [[ "$codename" != "jammy" && "$codename" != "noble" ]] && codename="jammy"
  # Note: if noble is still missing on some mirrors, jammy is a safer fallback
  add_apt_source rabbitmq \
    "deb [arch=amd64 signed-by=${_keyring_dir}/rabbitmq.gpg] https://deb1.rabbitmq.com/rabbitmq-erlang/ubuntu ${codename} ${codename} main
deb [arch=amd64 signed-by=${_keyring_dir}/rabbitmq.gpg] https://deb1.rabbitmq.com/rabbitmq-server/ubuntu ${codename} ${codename} main"
}

_setup_repo_nginx() {
  [[ -f /etc/apt/sources.list.d/nginx.list ]] && return
  add_apt_key nginx "https://nginx.org/keys/nginx_signing.key"
  add_apt_source nginx \
    "deb [signed-by=${_keyring_dir}/nginx.gpg] http://nginx.org/packages/ubuntu ${DISTRO_CODENAME} nginx"
}

_setup_repo_envoy() {
  [[ -f /etc/apt/sources.list.d/envoy.list ]] && return
  curl -fsSL https://apt.envoyproxy.io/signing.key \
    | sudo gpg --dearmor -o "${_keyring_dir}/envoy.gpg"
  add_apt_source envoy \
    "deb [arch=$(dpkg --print-architecture) signed-by=${_keyring_dir}/envoy.gpg] https://apt.envoyproxy.io ${DISTRO_CODENAME} main"
}

_setup_repo_kong() {
  [[ -f /etc/apt/sources.list.d/kong.list ]] && return
  add_apt_key kong "https://packages.konghq.com/public/gateway-39/gpg.key"
  local codename="$DISTRO_CODENAME"
  [[ "$codename" != "noble" && "$codename" != "jammy" ]] && codename="noble"
  add_apt_source kong \
    "deb [arch=amd64 signed-by=${_keyring_dir}/kong.gpg] https://packages.konghq.com/public/gateway-39/deb/ubuntu ${codename} main"
}

_setup_repo_bazel() {
  [[ -f /etc/apt/sources.list.d/bazel.list ]] && return
  add_apt_key bazel "https://bazel.build/bazel-release.pub.gpg"
  add_apt_source bazel \
    "deb [arch=amd64 signed-by=${_keyring_dir}/bazel.gpg] https://storage.googleapis.com/bazel-apt stable jdk1.8"
}

_setup_repo_grafana() {
  [[ -f /etc/apt/sources.list.d/grafana.list ]] && return
  add_apt_key grafana "https://packages.grafana.com/gpg.key"
  add_apt_source grafana \
    "deb [signed-by=${_keyring_dir}/grafana.gpg] https://packages.grafana.com/oss/deb stable main"
}

_setup_repo_nodesource() {
  [[ -f /etc/apt/sources.list.d/nodesource.list ]] && return
  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - &>/dev/null
}

_setup_repo_cuda() {
  [[ -f /etc/apt/sources.list.d/cuda.list ]] && return
  local codename="$DISTRO_CODENAME"
  local rel; rel=$(lsb_release -sr | tr -d '.')
  local cuda_deb="cuda-keyring_1.1-1_all.deb"
  local url
  if [[ "$DISTRO_ID" == "ubuntu" ]]; then
    url="https://developer.download.nvidia.com/compute/cuda/repos/ubuntu${rel}/x86_64/${cuda_deb}"
  else
    url="https://developer.download.nvidia.com/compute/cuda/repos/debian12/x86_64/${cuda_deb}"
  fi
  wget -qO "/tmp/${cuda_deb}" "$url"
  sudo dpkg -i "/tmp/${cuda_deb}"
}

# ─── Add all needed repos, then update once ───────────────────────────────────
setup_repos() {
  section "Configuring apt repositories"
  $INSTALL_POSTGRES      && _setup_repo_pgdg
  $INSTALL_CASSANDRA     && _setup_repo_cassandra
  $INSTALL_REDIS         && _setup_repo_redis
  $INSTALL_MONGODB       && _setup_repo_mongodb
  $INSTALL_CLICKHOUSE    && _setup_repo_clickhouse
  $INSTALL_ELASTICSEARCH && _setup_repo_elastic
  $INSTALL_RABBITMQ      && _setup_repo_rabbitmq
  $INSTALL_NGINX         && _setup_repo_nginx
  $INSTALL_ENVOY         && _setup_repo_envoy
  $INSTALL_KONG          && _setup_repo_kong
  $INSTALL_GRAFANA       && _setup_repo_grafana
  $INSTALL_BAZEL         && _setup_repo_bazel
  $INSTALL_NODE          && _setup_repo_nodesource
  $INSTALL_CUDA          && _gpu_present && _setup_repo_cuda
  sudo apt-get update -qq
  log_success "Repositories configured"
}

# ─── Individual install functions ────────────────────────────────────────────

install_postgres() {
  command -v psql &>/dev/null && { log_warn "PostgreSQL already installed, skipping"; return; }
  log_info "Installing PostgreSQL ${POSTGRES_VERSION} + pgvector + Citus..."

  # Citus apt repo (provides postgresql-<ver>-citus-* packages)
  if ! apt-cache show "postgresql-${POSTGRES_VERSION}-citus-12.1" &>/dev/null 2>&1; then
    log_info "Adding Citus apt repository..."
    curl -fsSL https://install.citusdata.com/community/deb.sh | sudo bash
  fi

  sudo apt-get install -y -qq \
    "postgresql-${POSTGRES_VERSION}" \
    "postgresql-${POSTGRES_VERSION}-pgvector" \
    "postgresql-${POSTGRES_VERSION}-citus-12.1" \
    "postgresql-client-${POSTGRES_VERSION}"

  # Citus must be in shared_preload_libraries — loaded at server start, not dynamically
  local pg_conf="/etc/postgresql/${POSTGRES_VERSION}/main/postgresql.conf"
  if ! sudo grep -q "citus" "${pg_conf}" 2>/dev/null; then
    log_info "Adding citus to shared_preload_libraries..."
    if sudo grep -q "^shared_preload_libraries" "${pg_conf}"; then
      sudo sed -i "s/^shared_preload_libraries\s*=\s*'\(.*\)'/shared_preload_libraries = '\1,citus'/" "${pg_conf}"
      # Clean up leading comma if list was empty
      sudo sed -i "s/= ',citus'/= 'citus'/" "${pg_conf}"
    else
      echo "shared_preload_libraries = 'citus'" | sudo tee -a "${pg_conf}" >/dev/null
    fi
  fi

  sudo systemctl enable postgresql
  log_success "PostgreSQL ${POSTGRES_VERSION} + pgvector + Citus installed"
  log_info  "Run: CREATE EXTENSION citus; in each database that needs sharding"
}

install_cassandra() {
  command -v cassandra &>/dev/null && { log_warn "Cassandra already installed, skipping"; return; }
  log_info "Installing Apache Cassandra 4.1..."
  # Cassandra needs Java; install if missing
  command -v java &>/dev/null || sudo apt-get install -y -qq openjdk-11-jre-headless
  sudo apt-get install -y -qq cassandra
  sudo systemctl enable cassandra
  log_success "Cassandra installed"
}

install_redis() {
  command -v redis-cli &>/dev/null && { log_warn "Redis already installed, skipping"; return; }
  log_info "Installing Redis..."
  sudo apt-get install -y -qq redis
  sudo systemctl enable redis-server
  log_success "Redis installed"
}

install_mongodb() {
  command -v mongod &>/dev/null && { log_warn "MongoDB already installed, skipping"; return; }
  log_info "Installing MongoDB 7.x..."
  sudo apt-get install -y -qq mongodb-org
  sudo systemctl enable mongod
  log_success "MongoDB installed"
}

install_clickhouse() {
  command -v clickhouse-server &>/dev/null && { log_warn "ClickHouse already installed, skipping"; return; }
  log_info "Installing ClickHouse..."
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    clickhouse-server clickhouse-client
  sudo systemctl enable clickhouse-server
  log_success "ClickHouse installed"
}

install_elasticsearch() {
  command -v elasticsearch &>/dev/null && { log_warn "Elasticsearch already installed, skipping"; return; }
  log_info "Installing Elasticsearch 8 + Kibana..."
  # ES8 needs at least 4GB RAM; warn if less
  local mem_kb; mem_kb=$(grep MemTotal /proc/meminfo | awk '{print $2}')
  (( mem_kb < 3000000 )) && log_warn "Elasticsearch recommends ≥4GB RAM (detected: $((mem_kb/1024))MB)"
  sudo apt-get install -y -qq elasticsearch kibana
  sudo systemctl enable elasticsearch kibana
  log_success "Elasticsearch 8 + Kibana installed"
  log_warn "Elasticsearch 8 has security enabled by default."
  log_warn "Initial elastic password is shown once during first 'systemctl start elasticsearch'."
}

# ─── Mise detection ───────────────────────────────────────────────────────────
_get_mise_bin() {
  if command -v mise &>/dev/null; then
    command -v mise
  elif [[ -x "$HOME/.local/bin/mise" ]]; then
    echo "$HOME/.local/bin/mise"
  elif [[ -n "${SUDO_USER:-}" ]] && [[ -x "/home/$SUDO_USER/.local/bin/mise" ]]; then
    echo "/home/$SUDO_USER/.local/bin/mise"
  elif [[ -x "/usr/local/bin/mise" ]]; then
    echo "/usr/local/bin/mise"
  else
    return 1
  fi
}

install_kafka() {
  [[ -d /opt/kafka ]] && { log_warn "Kafka already installed at /opt/kafka, skipping"; return; }
  log_info "Installing Kafka ${KAFKA_VERSION}..."
  
  # Java is mandatory
  command -v java &>/dev/null || sudo apt-get install -y -qq openjdk-21-jre-headless

  local tarball="kafka_${KAFKA_SCALA}-${KAFKA_VERSION}.tgz"
  local tmp_tarball="/tmp/${tarball}"
  
  # Try multiple mirror patterns
  local urls=(
    "https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/${tarball}"
    "https://downloads.apache.org/kafka/${KAFKA_VERSION}/${tarball}"
  )

  sudo rm -f "$tmp_tarball"
  local downloaded=false
  for url in "${urls[@]}"; do
    log_info "Trying to download Kafka from: $url"
    if sudo wget -qO "$tmp_tarball" "$url"; then
      downloaded=true
      break
    fi
  done

  if [[ "$downloaded" == "false" ]]; then
    log_error "Failed to download Kafka ${KAFKA_VERSION} from all sources."
    return 1
  fi

  sudo mkdir -p /opt/kafka
  sudo tar -xzf "$tmp_tarball" -C /opt
  sudo rm -rf /opt/kafka
  sudo mv "/opt/kafka_${KAFKA_SCALA}-${KAFKA_VERSION}" /opt/kafka
  sudo rm -f "$tmp_tarball"

  # System-wide PATH for Kafka binaries
  echo "export PATH=\$PATH:/opt/kafka/bin" | sudo tee /etc/profile.d/kafka.sh >/dev/null
  export PATH=$PATH:/opt/kafka/bin

  # Permanent log directory
  sudo mkdir -p /var/lib/kafka-logs
  sudo useradd -r -s /bin/false kafka 2>/dev/null || true
  sudo chown -R kafka:kafka /var/lib/kafka-logs /opt/kafka

  # Update log.dirs in server.properties to be persistent
  sudo sed -i 's|log.dirs=.*|log.dirs=/var/lib/kafka-logs|' /opt/kafka/config/kraft/server.properties

  # KRaft setup
  if [[ ! -f /opt/kafka/config/kraft/server.properties.formatted ]]; then
    log_info "Formatting Kafka storage (KRaft mode)..."
    local uuid; uuid=$(/opt/kafka/bin/kafka-storage.sh random-uuid)
    sudo -u kafka /opt/kafka/bin/kafka-storage.sh format \
      -t "$uuid" \
      -c /opt/kafka/config/kraft/server.properties
    sudo touch /opt/kafka/config/kraft/server.properties.formatted
  fi

  # Systemd unit
  write_systemd_unit kafka "Apache Kafka" kafka \
    "/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/kraft/server.properties" \
    "/opt/kafka"
  sudo systemctl enable kafka
  log_success "Kafka ${KAFKA_VERSION} installed (active on :9092)"
}

install_rabbitmq() {
  command -v rabbitmqctl &>/dev/null && { log_warn "RabbitMQ already installed, skipping"; return; }
  log_info "Installing RabbitMQ..."
  sudo apt-get install -y -qq \
    erlang-base erlang-asn1 erlang-crypto erlang-eldap \
    erlang-ftp erlang-inets erlang-mnesia erlang-os-mon \
    erlang-parsetools erlang-public-key erlang-runtime-tools \
    erlang-snmp erlang-ssl erlang-syntax-tools erlang-tftp \
    erlang-tools erlang-xmerl
  sudo apt-get install -y -qq rabbitmq-server
  sudo systemctl enable rabbitmq-server
  # Enable management plugin for web UI
  sudo rabbitmq-plugins enable rabbitmq_management 2>/dev/null || true
  log_success "RabbitMQ installed (management UI on :15672)"
}

install_memcached() {
  command -v memcached &>/dev/null && { log_warn "Memcached already installed, skipping"; return; }
  log_info "Installing Memcached..."
  sudo apt-get install -y -qq memcached
  sudo systemctl enable memcached
  log_success "Memcached installed"
}

install_minio() {
  command -v minio &>/dev/null && { log_warn "MinIO already installed, skipping"; return; }
  log_info "Installing MinIO..."
  install_binary \
    "https://dl.min.io/server/minio/release/linux-amd64/minio" \
    /usr/local/bin/minio
  sudo useradd -r -s /bin/false minio-user 2>/dev/null || true
  sudo mkdir -p /var/lib/minio/data
  sudo chown -R minio-user:minio-user /var/lib/minio
  write_systemd_unit minio "MinIO Object Storage" minio-user \
    "/usr/local/bin/minio server /var/lib/minio/data --console-address ':9001'" \
    "/var/lib/minio"
  sudo systemctl enable minio
  log_success "MinIO installed (API :9000, Console :9001)"
}

install_nginx() {
  command -v nginx &>/dev/null && { log_warn "Nginx already installed, skipping"; return; }
  log_info "Installing Nginx..."
  sudo apt-get install -y -qq nginx
  sudo systemctl enable nginx
  log_success "Nginx installed"
}

install_haproxy() {
  command -v haproxy &>/dev/null && { log_warn "HAProxy already installed, skipping"; return; }
  log_info "Installing HAProxy..."
  sudo apt-get install -y -qq haproxy
  sudo systemctl enable haproxy
  log_success "HAProxy installed"
}

install_envoy() {
  command -v envoy &>/dev/null && { log_warn "Envoy already installed, skipping"; return; }
  log_info "Installing Envoy..."
  sudo apt-get install -y -qq envoy
  log_success "Envoy installed (no service; start with: envoy -c /path/to/config.yaml)"
}

install_kong() {
  command -v kong &>/dev/null && { log_warn "Kong already installed, skipping"; return; }
  log_info "Installing Kong OSS..."
  sudo apt-get install -y -qq kong
  sudo systemctl enable kong
  log_success "Kong installed (proxy :8000, admin :8001)"
  log_warn "Kong requires a PostgreSQL or Cassandra backend. Run 'kong migrations bootstrap' after configuring /etc/kong/kong.conf"
}

install_prometheus() {
  command -v prometheus &>/dev/null && { log_warn "Prometheus already installed, skipping"; return; }
  log_info "Installing Prometheus..."
  local tag; tag=$(get_latest_github_release "prometheus/prometheus")
  local ver="${tag#v}"
  local tarball="prometheus-${ver}.linux-amd64.tar.gz"
  wget -qO "/tmp/${tarball}" \
    "https://github.com/prometheus/prometheus/releases/download/${tag}/${tarball}"
  sudo tar -xzf "/tmp/${tarball}" -C /opt
  sudo mv "/opt/prometheus-${ver}.linux-amd64" /opt/prometheus
  sudo ln -sf /opt/prometheus/prometheus /usr/local/bin/prometheus
  sudo ln -sf /opt/prometheus/promtool   /usr/local/bin/promtool

  sudo useradd -r -s /bin/false prometheus 2>/dev/null || true
  sudo mkdir -p /var/lib/prometheus /etc/prometheus
  sudo cp /opt/prometheus/prometheus.yml /etc/prometheus/prometheus.yml
  sudo chown -R prometheus:prometheus /var/lib/prometheus /etc/prometheus
  write_systemd_unit prometheus "Prometheus Monitoring" prometheus \
    "/usr/local/bin/prometheus --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/var/lib/prometheus" \
    "/var/lib/prometheus"
  sudo systemctl enable prometheus
  log_success "Prometheus ${ver} installed"
}

install_grafana() {
  command -v grafana-server &>/dev/null && { log_warn "Grafana already installed, skipping"; return; }
  log_info "Installing Grafana OSS..."
  sudo apt-get install -y -qq grafana-oss
  sudo systemctl enable grafana-server
  log_success "Grafana installed (default login: admin/admin on :3000)"
}

install_jaeger() {
  command -v jaeger-all-in-one &>/dev/null && { log_warn "Jaeger already installed, skipping"; return; }
  log_info "Installing Jaeger all-in-one..."
  local tag; tag=$(get_latest_github_release "jaegertracing/jaeger")
  local ver="${tag#v}"
  local tarball="jaeger-${ver}-linux-amd64.tar.gz"
  wget -qO "/tmp/${tarball}" \
    "https://github.com/jaegertracing/jaeger/releases/download/${tag}/${tarball}"
  sudo tar -xzf "/tmp/${tarball}" -C /tmp
  sudo mv "/tmp/jaeger-${ver}-linux-amd64/jaeger-all-in-one" /usr/local/bin/jaeger-all-in-one

  sudo useradd -r -s /bin/false jaeger 2>/dev/null || true
  sudo mkdir -p /var/lib/jaeger
  sudo chown jaeger:jaeger /var/lib/jaeger
  write_systemd_unit jaeger "Jaeger Distributed Tracing" jaeger \
    "/usr/local/bin/jaeger-all-in-one --badger.ephemeral=false --badger.directory-value=/var/lib/jaeger/data --badger.directory-key=/var/lib/jaeger/key" \
    "/var/lib/jaeger"
  sudo systemctl enable jaeger
  log_success "Jaeger ${ver} installed (UI: :16686)"
}

install_otelcol() {
  command -v otelcol-contrib &>/dev/null && { log_warn "OTel Collector already installed, skipping"; return; }
  log_info "Installing OpenTelemetry Collector (contrib)..."
  local tag; tag=$(get_latest_github_release "open-telemetry/opentelemetry-collector-releases")
  local ver="${tag#v}"
  local tarball="otelcol-contrib_${ver}_linux_amd64.tar.gz"
  wget -qO "/tmp/${tarball}" \
    "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/${tag}/${tarball}"
  sudo tar -xzf "/tmp/${tarball}" -C /tmp otelcol-contrib
  sudo mv /tmp/otelcol-contrib /usr/local/bin/otelcol-contrib

  sudo useradd -r -s /bin/false otelcol 2>/dev/null || true
  sudo mkdir -p /etc/otelcol
  sudo tee /etc/otelcol/config.yaml >/dev/null <<'OTEL_CONF'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:

exporters:
  debug:
    verbosity: normal

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [debug]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [debug]
    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [debug]
OTEL_CONF

  sudo chown -R otelcol:otelcol /etc/otelcol
  write_systemd_unit otelcol "OpenTelemetry Collector" otelcol \
    "/usr/local/bin/otelcol-contrib --config=/etc/otelcol/config.yaml"
  sudo systemctl enable otelcol
  log_success "OTel Collector ${ver} installed (gRPC :4317, HTTP :4318)"
}

install_java() {
  command -v java &>/dev/null && { log_warn "Java already installed, skipping"; return; }
  log_info "Installing OpenJDK 21 + Maven..."
  sudo apt-get install -y -qq openjdk-21-jdk maven
  log_success "Java $(java -version 2>&1 | head -1) + Maven installed"
}

install_go() {
  command -v go &>/dev/null && { log_warn "Go already installed, skipping"; return; }
  log_info "Installing Go (latest)..."
  local latest_ver
  latest_ver=$(curl -fsSL "https://go.dev/VERSION?m=text" | head -1)
  local tarball="${latest_ver}.linux-amd64.tar.gz"
  wget -qO "/tmp/${tarball}" "https://go.dev/dl/${tarball}"
  sudo rm -rf /usr/local/go
  sudo tar -C /usr/local -xzf "/tmp/${tarball}"
  # Add to system-wide PATH
  echo 'export PATH=$PATH:/usr/local/go/bin' \
    | sudo tee /etc/profile.d/golang.sh >/dev/null
  export PATH=$PATH:/usr/local/go/bin
  log_success "Go ${latest_ver} installed"
}

install_node() {
  command -v node &>/dev/null && { log_warn "Node.js already installed, skipping"; return; }
  log_info "Installing Node.js 20 LTS..."
  sudo apt-get install -y -qq nodejs
  log_success "Node.js $(node --version) installed"
}

install_rust() {
  command -v rustc &>/dev/null && { log_warn "Rust already installed, skipping"; return; }
  log_info "Installing Rust via rustup..."
  # rustup must NOT be run as root
  if [[ "$EUID" -eq 0 ]]; then
    log_warn "Running as root; Rust will be installed for \$SUDO_USER (${SUDO_USER:-root})"
    su - "${SUDO_USER:-$(logname 2>/dev/null || echo root)}" -c \
      'curl --proto "=https" --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --no-modify-path'
  else
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --no-modify-path
  fi
  echo 'export PATH=$PATH:$HOME/.cargo/bin' \
    | sudo tee /etc/profile.d/cargo.sh >/dev/null
  log_success "Rust installed"
}

install_bazel() {
  command -v bazel &>/dev/null && { log_warn "Bazel already installed, skipping"; return; }
  log_info "Installing Bazel..."
  sudo apt-get install -y -qq bazel
  log_success "Bazel $(bazel --version) installed"
}

install_qdrant() {
  command -v qdrant &>/dev/null && { log_warn "Qdrant already installed, skipping"; return; }
  log_info "Installing Qdrant..."
  local tag; tag=$(get_latest_github_release "qdrant/qdrant")
  local ver="${tag#v}"
  local deb="qdrant_${ver}-1_amd64.deb"
  wget -qO "/tmp/${deb}" \
    "https://github.com/qdrant/qdrant/releases/download/${tag}/${deb}"
  sudo dpkg -i "/tmp/${deb}"
  sudo systemctl enable qdrant
  log_success "Qdrant ${ver} installed (:6333 REST, :6334 gRPC)"
}

install_ollama() {
  command -v ollama &>/dev/null && { log_warn "Ollama already installed, skipping"; return; }
  log_info "Installing Ollama..."
  curl -fsSL https://ollama.com/install.sh | sh
  sudo systemctl enable ollama
  log_success "Ollama installed (:11434)"
  log_info "Pull a model with: ollama pull llama3.2"
}

install_spark() {
  [[ -d /opt/spark ]] && { log_warn "Spark already installed at /opt/spark, skipping"; return; }
  log_info "Installing Apache Spark ${SPARK_VERSION}..."
  command -v java &>/dev/null || sudo apt-get install -y -qq openjdk-21-jre-headless
  local tarball="spark-${SPARK_VERSION}-bin-hadoop${SPARK_HADOOP}.tgz"
  local url="https://downloads.apache.org/spark/spark-${SPARK_VERSION}/${tarball}"
  wget -qO "/tmp/${tarball}" "$url"
  sudo tar -xzf "/tmp/${tarball}" -C /opt
  sudo mv "/opt/spark-${SPARK_VERSION}-bin-hadoop${SPARK_HADOOP}" /opt/spark
  echo 'export SPARK_HOME=/opt/spark' \
    | sudo tee /etc/profile.d/spark.sh >/dev/null
  echo 'export PATH=$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin' \
    >> /etc/profile.d/spark.sh
  log_success "Spark ${SPARK_VERSION} installed (SPARK_HOME=/opt/spark)"
  log_info "Start local master: /opt/spark/sbin/start-master.sh"
}

install_cuda() {
  if ! _gpu_present; then
    log_warn "No NVIDIA GPU detected — skipping CUDA installation"
    return
  fi
  command -v nvcc &>/dev/null && { log_warn "CUDA already installed, skipping"; return; }
  log_info "Installing CUDA toolkit..."
  sudo apt-get install -y -qq cuda-toolkit
  echo 'export PATH=/usr/local/cuda/bin:$PATH' \
    | sudo tee /etc/profile.d/cuda.sh >/dev/null
  echo 'export LD_LIBRARY_PATH=/usr/local/cuda/lib64:$LD_LIBRARY_PATH' \
    >> /etc/profile.d/cuda.sh
  log_success "CUDA installed (nvcc: $(nvcc --version 2>&1 | grep release | awk '{print $5}'))"
}

# ─── Conda / AI stack ─────────────────────────────────────────────────────────

_ensure_conda() {
  if command -v conda &>/dev/null; then return; fi
  log_info "conda not found — installing Miniconda3..."
  local installer="/tmp/miniconda.sh"
  wget -qO "$installer" \
    "https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh"
  bash "$installer" -b -p "$HOME/miniconda3"
  rm "$installer"
  export PATH="$HOME/miniconda3/bin:$PATH"
  conda init bash &>/dev/null || true
  log_success "Miniconda3 installed at ~/miniconda3"
}

install_ai_stack() {
  _ensure_conda

  # Create dev env if it doesn't exist
  if ! conda info --envs | awk '{print $1}' | grep -qx "dev"; then
    log_info "Creating conda env 'dev' with Python 3.12..."
    conda create -n dev python=3.12 -y -q
  else
    log_info "conda env 'dev' already exists"
  fi

  # Determine torch index (CPU vs CUDA)
  local torch_index="https://download.pytorch.org/whl/cpu"
  if _gpu_present && $INSTALL_CUDA; then
    torch_index="https://download.pytorch.org/whl/cu124"
    log_info "GPU detected — installing CUDA PyTorch"
  else
    log_info "CPU-only PyTorch (pass --cuda for GPU build)"
  fi

  local ort_pkg="onnxruntime"
  _gpu_present && $INSTALL_CUDA && ort_pkg="onnxruntime-gpu"

  local pip="conda run -n dev pip install -q --upgrade"

  log_info "Installing core ML packages..."
  $pip numpy pandas polars scikit-learn

  log_info "Installing PyTorch..."
  $pip torch torchvision torchaudio \
    --index-url "$torch_index"

  log_info "Installing HuggingFace ecosystem..."
  $pip transformers datasets accelerate peft \
    sentence-transformers huggingface_hub[cli] \
    "$ort_pkg"

  log_info "Installing LLM / RAG frameworks..."
  $pip \
    langchain langchain-huggingface langchain-community \
    llama-index openai

  log_info "Installing MLOps / orchestration..."
  local python_ver
  python_ver=$(conda run -n dev python --version 2>&1 | awk '{print $2}' | cut -d. -f1,2)
  $pip mlflow[extras] prefect "dvc[s3]" "ray[default]" "dask[complete]"
  conda run -n dev pip install -q \
    "apache-airflow==${AIRFLOW_VERSION}" \
    --constraint "https://raw.githubusercontent.com/apache/airflow/constraints-${AIRFLOW_VERSION}/constraints-${python_ver}.txt"

  log_info "Installing model serving..."
  $pip torchserve torch-model-archiver torch-workflow-archiver \
    bentoml "tritonclient[all]"

  log_info "Installing vector / feature stores..."
  $pip chromadb feast milvus-lite

  log_info "Installing data quality + PySpark..."
  $pip great-expectations pyspark

  log_info "Installing Jupyter Lab..."
  $pip jupyterlab

  log_success "AI stack installed in conda env 'dev'"
  log_info "Activate with: conda activate dev"
  log_info "Start Jupyter: conda run -n dev jupyter lab --ip=0.0.0.0 --port=8888 --no-browser"
}

# ─── Service startup ──────────────────────────────────────────────────────────

start_services() {
  section "Starting services"
  sudo systemctl daemon-reload

  _start() {
    local svc="$1"
    if systemctl list-unit-files "${svc}.service" &>/dev/null; then
      if sudo systemctl start "$svc" 2>/dev/null; then
        log_success "Started: $svc"
      else
        log_warn "Could not start: $svc (check: journalctl -u $svc)"
      fi
    fi
  }

  $INSTALL_POSTGRES      && _start postgresql || true
  $INSTALL_REDIS         && _start redis-server || true
  $INSTALL_MONGODB       && _start mongod || true
  $INSTALL_CLICKHOUSE    && _start clickhouse-server || true
  $INSTALL_ELASTICSEARCH && _start elasticsearch || true
  $INSTALL_ELASTICSEARCH && _start kibana || true
  $INSTALL_RABBITMQ      && _start rabbitmq-server || true
  $INSTALL_MEMCACHED     && _start memcached || true
  $INSTALL_MINIO         && _start minio || true
  $INSTALL_NGINX         && _start nginx || true
  $INSTALL_HAPROXY       && _start haproxy || true
  $INSTALL_KONG          && _start kong || true
  $INSTALL_PROMETHEUS    && _start prometheus || true
  $INSTALL_GRAFANA       && _start grafana-server || true
  $INSTALL_JAEGER        && _start jaeger || true
  $INSTALL_OTELCOL       && _start otelcol || true
  $INSTALL_QDRANT        && _start qdrant || true
  $INSTALL_OLLAMA        && _start ollama || true
  $INSTALL_KAFKA         && _start kafka || true

  # Cassandra is slow to start; note only
  $INSTALL_CASSANDRA && {
    sudo systemctl start cassandra 2>/dev/null || true
    log_info "Cassandra started (may take ~30s to become ready; check: nodetool status)"
  }
  return 0
}

# ─── Summary table ────────────────────────────────────────────────────────────

print_summary() {
  section "Installation Summary"
  printf "%-22s %-8s %-22s %s\n" "Service" "Port(s)" "Endpoint" "Status"
  printf "%-22s %-8s %-22s %s\n" "-------" "-------" "--------" "------"

  _row() {
    local name="$1" ports="$2" url="$3" svc="$4"
    local status="—"
    if systemctl list-unit-files "${svc}.service" &>/dev/null 2>&1; then
      status=$(systemctl is-active "$svc" 2>/dev/null || echo "inactive")
      [[ "$status" == "active" ]] && status="${GREEN}active${NC}" || status="${RED}${status}${NC}"
    fi
    printf "%-22s %-8s %-22s " "$name" "$ports" "$url"
    echo -e "$status"
    return 0
  }

  $INSTALL_POSTGRES      && _row "PostgreSQL ${POSTGRES_VERSION}"  "5432"       "localhost:5432"       postgresql
  $INSTALL_CASSANDRA     && _row "Cassandra 4.1"        "9042"       "localhost:9042"       cassandra
  $INSTALL_REDIS         && _row "Redis"                "6379"       "localhost:6379"       redis-server
  $INSTALL_MONGODB       && _row "MongoDB 7.x"          "27017"      "localhost:27017"      mongod
  $INSTALL_CLICKHOUSE    && _row "ClickHouse"           "9000,8123"  "localhost:8123"       clickhouse-server
  $INSTALL_ELASTICSEARCH && _row "Elasticsearch 8"      "9200"       "https://localhost:9200" elasticsearch
  $INSTALL_ELASTICSEARCH && _row "Kibana"               "5601"       "http://localhost:5601" kibana
  $INSTALL_KAFKA         && _row "Kafka (KRaft)"        "9092"       "localhost:9092"       kafka
  $INSTALL_RABBITMQ      && _row "RabbitMQ"             "5672,15672" "http://localhost:15672" rabbitmq-server
  $INSTALL_MEMCACHED     && _row "Memcached"            "11211"      "localhost:11211"      memcached
  $INSTALL_MINIO         && _row "MinIO"                "9000,9001"  "http://localhost:9001" minio
  $INSTALL_NGINX         && _row "Nginx"                "80,443"     "http://localhost"     nginx
  $INSTALL_HAPROXY       && _row "HAProxy"              "80,8404"    "http://localhost:8404/stats" haproxy
  $INSTALL_KONG          && _row "Kong OSS"             "8000,8001"  "http://localhost:8001" kong
  $INSTALL_PROMETHEUS    && _row "Prometheus"           "9090"       "http://localhost:9090" prometheus
  $INSTALL_GRAFANA       && _row "Grafana OSS"          "3000"       "http://localhost:3000" grafana-server
  $INSTALL_JAEGER        && _row "Jaeger"               "16686"      "http://localhost:16686" jaeger
  $INSTALL_OTELCOL       && _row "OTel Collector"       "4317,4318"  "grpc://localhost:4317" otelcol
  $INSTALL_QDRANT        && _row "Qdrant"               "6333,6334"  "http://localhost:6333" qdrant
  $INSTALL_OLLAMA        && _row "Ollama"               "11434"      "http://localhost:11434" ollama

  echo ""
  $INSTALL_JAVA    && log_success "Java:   $(java -version 2>&1 | head -1)"
  $INSTALL_GO      && log_success "Go:     $(go version 2>/dev/null || echo 'restart shell to use')"
  $INSTALL_NODE    && log_success "Node:   $(node --version 2>/dev/null)"
  $INSTALL_RUST    && log_success "Rust:   (restart shell, then: rustc --version)"
  $INSTALL_BAZEL   && log_success "Bazel:  $(bazel --version 2>/dev/null | head -1)"
  $INSTALL_SPARK   && log_success "Spark:  ${SPARK_VERSION} at /opt/spark"
  $INSTALL_AI_STACK && log_success "AI env: conda activate dev"
}

# ─── Main ─────────────────────────────────────────────────────────────────────
main() {
  echo -e "${BOLD}${CYAN}"
  echo "╔══════════════════════════════════════════════════════════╗"
  echo "║     Backend + ML Infrastructure Setup                   ║"
  echo "╚══════════════════════════════════════════════════════════╝"
  echo -e "${NC}"

  parse_args "$@"
  [[ "$_any_flag" == false ]] && interactive_select

  check_sudo
  detect_os
  install_baseline
  setup_repos

  section "Installing components"

  $INSTALL_JAVA          && install_java || true
  $INSTALL_POSTGRES      && install_postgres || true
  $INSTALL_CASSANDRA     && install_cassandra || true
  $INSTALL_REDIS         && install_redis || true
  $INSTALL_MONGODB       && install_mongodb || true
  $INSTALL_CLICKHOUSE    && install_clickhouse || true
  $INSTALL_ELASTICSEARCH && install_elasticsearch || true
  $INSTALL_KAFKA         && install_kafka || true
  $INSTALL_RABBITMQ      && install_rabbitmq || true
  $INSTALL_MEMCACHED     && install_memcached || true
  $INSTALL_MINIO         && install_minio || true
  $INSTALL_NGINX         && install_nginx || true
  $INSTALL_HAPROXY       && install_haproxy || true
  $INSTALL_ENVOY         && install_envoy || true
  $INSTALL_KONG          && install_kong || true
  $INSTALL_PROMETHEUS    && install_prometheus || true
  $INSTALL_GRAFANA       && install_grafana || true
  $INSTALL_JAEGER        && install_jaeger || true
  $INSTALL_OTELCOL       && install_otelcol || true
  $INSTALL_GO            && install_go || true
  $INSTALL_NODE          && install_node || true
  $INSTALL_RUST          && install_rust || true
  $INSTALL_BAZEL         && install_bazel || true
  $INSTALL_QDRANT        && install_qdrant || true
  $INSTALL_OLLAMA        && install_ollama || true
  $INSTALL_SPARK         && install_spark || true
  $INSTALL_CUDA          && install_cuda || true
  $INSTALL_AI_STACK      && install_ai_stack || true

  start_services

  print_summary || true

  echo ""
  log_success "Setup complete! Log out and back in (or run: source /etc/profile.d/*.sh) to reload PATH."
  return 0
}

main "$@"
