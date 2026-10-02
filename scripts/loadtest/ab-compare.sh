#!/usr/bin/env bash
# A/B load test driver: same MySQL, same k6 scripts, same settings, several builds.
# Build one image per commit first, e.g. from a worktree of each commit:
#   git worktree add ../lt-<sha> <sha> && docker build -t atompay-lt:<sha> ../lt-<sha>
# (commits before a0bdb41 need this repo's Dockerfile copied in and the
#  spring-boot-maven-plugin repackage goal bound — see docs/loadtest-results.md)
# Usage: VERSIONS="bc78151 ed49f78 c659cfa" bash scripts/loadtest/ab-compare.sh
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd -W 2>/dev/null || pwd)"
OUT="${OUT:-$REPO/docs/loadtest-$(date +%F)}"
mkdir -p "$OUT"
SCRIPTS="$REPO/scripts/loadtest"
NET=atompay-lt
JWT=$(python -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())")

docker network create $NET >/dev/null 2>&1
docker rm -f lt-mysql lt-app >/dev/null 2>&1
docker run -d --name lt-mysql --network $NET -e MYSQL_ROOT_PASSWORD=root mysql:8.1.0 >/dev/null
until docker exec lt-mysql mysqladmin ping -uroot -proot --silent 2>/dev/null; do sleep 2; done
sleep 5

run() { # version scenario pool
  local v=$1 s=$2 pool=$3 tag="$1-$2-pool$3"
  docker exec lt-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS cardpay; CREATE DATABASE cardpay;" 2>/dev/null
  docker rm -f lt-app >/dev/null 2>&1
  docker run -d --name lt-app --network $NET \
    -e SPRING_PROFILES_ACTIVE=mysql,loadtest \
    -e SPRING_DATASOURCE_URL="jdbc:mysql://lt-mysql:3306/cardpay?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
    -e SPRING_DATASOURCE_USERNAME=root -e SPRING_DATASOURCE_PASSWORD=root \
    -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$pool \
    -e SPRING_JPA_SHOW_SQL=false \
    -e RATELIMIT_PAYMENTS_LIMIT=1000000000 \
    -e JWT_SECRET="$JWT" \
    atompay-lt:$v >/dev/null
  for _ in $(seq 1 90); do
    docker run --rm --network $NET curlimages/curl -fs http://lt-app:8080/actuator/health 2>/dev/null | grep -q UP && break
    sleep 2
  done
  # warmup (discarded), then the measured run
  MSYS_NO_PATHCONV=1 docker run --rm --network $NET -v "$SCRIPTS:/scripts:ro" -e BASE_URL=http://lt-app:8080 \
    -e DURATION=10s grafana/k6 run -q /scripts/$s.js >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 docker run --rm --network $NET -v "$SCRIPTS:/scripts:ro" -v "$OUT:/out" -e BASE_URL=http://lt-app:8080 \
    grafana/k6 run -q --summary-trend-stats "avg,med,p(95),p(99),max" --summary-export=/out/$tag.json /scripts/$s.js >/dev/null 2>&1
  echo "done $tag"
}

for v in ${VERSIONS:-bc78151 ed49f78 c659cfa}; do
  run $v contention 60
  run $v distributed 60
  run $v distributed 10
done
docker rm -f lt-app lt-mysql >/dev/null 2>&1
echo ALL_DONE
