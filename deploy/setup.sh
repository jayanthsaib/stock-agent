#!/usr/bin/env bash
# =============================================================================
# setup.sh — One-shot bootstrap for AI Stock Trading Agent on Oracle Cloud
#             Ubuntu 22.04 ARM (A1.Flex, Always Free)
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/jayanthsaib/stock-agent/master/deploy/setup.sh | bash
# =============================================================================
set -euo pipefail

REPO_URL="https://github.com/jayanthsaib/stock-agent.git"
APP_DIR="/home/ubuntu/stock-agent"
SERVICE_NAME="stock-agent"
JAR_PATH="$APP_DIR/target/stock-agent-1.0-SNAPSHOT.jar"

info()  { echo -e "\e[32m[INFO]\e[0m  $*"; }
warn()  { echo -e "\e[33m[WARN]\e[0m  $*"; }
error() { echo -e "\e[31m[ERROR]\e[0m $*" >&2; exit 1; }

# ── 1. Require Ubuntu ─────────────────────────────────────────────────────────
[[ "$(uname -s)" == "Linux" ]] || error "This script is for Linux only."
id ubuntu &>/dev/null || error "User 'ubuntu' not found. Run this on an Oracle Ubuntu VM."

info "=== AI Stock Trading Agent — Server Bootstrap ==="
info "Target directory: $APP_DIR"

# ── 2. Install Java 21 (Eclipse Temurin) ──────────────────────────────────────
info "Installing Java 21 (Eclipse Temurin)..."
sudo apt-get update -q
sudo apt-get install -y wget apt-transport-https gnupg

wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg

echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] \
  https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list > /dev/null

sudo apt-get update -q
sudo apt-get install -y temurin-21-jdk

java -version 2>&1 | grep -q "21" || error "Java 21 installation failed."
info "Java 21 installed: $(java -version 2>&1 | head -1)"

# ── 3. Install Maven 3.9 ──────────────────────────────────────────────────────
info "Installing Maven 3.9..."
MVN_VERSION="3.9.6"
MVN_URL="https://archive.apache.org/dist/maven/maven-3/${MVN_VERSION}/binaries/apache-maven-${MVN_VERSION}-bin.tar.gz"

sudo mkdir -p /opt/maven
wget -q "$MVN_URL" -O /tmp/maven.tar.gz
sudo tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1
rm /tmp/maven.tar.gz

sudo tee /etc/profile.d/maven.sh > /dev/null <<'MVNENV'
export M2_HOME=/opt/maven
export PATH=$M2_HOME/bin:$PATH
MVNENV
export M2_HOME=/opt/maven
export PATH=$M2_HOME/bin:$PATH

mvn -version | grep -q "3.9" || error "Maven 3.9 installation failed."
info "Maven installed: $(mvn -version 2>&1 | head -1)"

# ── 4. Clone / update repository ─────────────────────────────────────────────
if [[ -d "$APP_DIR/.git" ]]; then
  info "Repository already exists — pulling latest changes..."
  git -C "$APP_DIR" pull
else
  info "Cloning repository from $REPO_URL..."
  git clone "$REPO_URL" "$APP_DIR"
fi

# ── 5. Configure environment variables ───────────────────────────────────────
ENV_FILE="$APP_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  cp "$APP_DIR/.env.example" "$ENV_FILE"
  warn "──────────────────────────────────────────────────────────────────"
  warn "  IMPORTANT: Fill in your credentials in $ENV_FILE"
  warn "  Required: ANGEL_ONE_* and TELEGRAM_* variables"
  warn "──────────────────────────────────────────────────────────────────"
  echo ""
  read -rp "Press ENTER after you have edited $ENV_FILE to continue setup... "
  echo ""
  # Validate that the user actually filled in the file (not just example values)
  if grep -q "your_" "$ENV_FILE" 2>/dev/null; then
    warn "Placeholder values detected in .env — the agent may not function correctly."
    warn "Edit $ENV_FILE and restart the service when ready."
  fi
else
  info ".env already exists — skipping credential prompt."
fi

# ── 6. Build fat JAR ─────────────────────────────────────────────────────────
info "Building application (mvn package -DskipTests)..."
cd "$APP_DIR"
mvn package -DskipTests -q
[[ -f "$JAR_PATH" ]] || error "Build failed — JAR not found at $JAR_PATH"
info "Build complete: $JAR_PATH"

# ── 7. Create log directory ───────────────────────────────────────────────────
mkdir -p "$APP_DIR/logs"
chown ubuntu:ubuntu "$APP_DIR/logs" 2>/dev/null || true

# ── 8. Install systemd service ────────────────────────────────────────────────
info "Installing systemd service ($SERVICE_NAME)..."
sudo cp "$APP_DIR/deploy/stock-agent.service" "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

# ── 9. Open port 8080 in iptables ────────────────────────────────────────────
info "Opening port 8080 in iptables..."
# Only add rule if it doesn't already exist
if ! sudo iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null; then
  sudo iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
fi

# Persist iptables rules across reboots
if command -v netfilter-persistent &>/dev/null; then
  sudo netfilter-persistent save
else
  sudo apt-get install -y iptables-persistent -q <<< "yes
yes"
  sudo netfilter-persistent save
fi

# ── 10. Summary ───────────────────────────────────────────────────────────────
PUBLIC_IP=$(curl -s --max-time 5 http://checkip.amazonaws.com || echo "<public-ip>")

echo ""
info "=== Setup Complete ==="
echo ""
echo "  Service status:  sudo systemctl status $SERVICE_NAME"
echo "  Live logs:       tail -f $APP_DIR/logs/agent.log"
echo "  Local health:    curl http://localhost:8080/api/status"
echo "  Dashboard:       http://${PUBLIC_IP}:8080/ui/dashboard"
echo ""
echo "  Next steps:"
echo "  1. Verify the service is running:  sudo systemctl status $SERVICE_NAME"
echo "  2. Authenticate with Angel One:    curl -X POST http://localhost:8080/api/broker/login"
echo "  3. Watch the logs for the first market scan."
echo ""
