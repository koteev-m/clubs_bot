FROM ubuntu@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90
COPY packages.txt /expected-packages.txt
RUN apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3=3.12.3-0ubuntu2.1 ruby=1:3.2~ubuntu1 ruby-psych=5.0.2-2build2 strace=6.8-0ubuntu2 git=1:2.43.0-1ubuntu7.3 openssh-client=1:9.6p1-3ubuntu13.19 util-linux=2.39.3-9ubuntu6.6 && rm -rf /var/lib/apt/lists/*
COPY --chmod=0555 docker-compose-linux-aarch64 /usr/local/bin/docker-compose
RUN echo '4b5c42952b7dd81f508d01a771df2a9e5dbffe9b8c5c7d983e738504ad38f056  /usr/local/bin/docker-compose' | sha256sum -c - && mkdir -p /work/runner-temp /work/tests && chown -R 1000:1000 /work && chmod 700 /work/runner-temp && dpkg-query -W -f='${Package}=${Version}\n' > /toolchain-packages.txt
RUN mkdir -p /opt/clubs-bot-stage /run/user/1000 && chown 1000:1000 /opt/clubs-bot-stage /run/user/1000 && chmod 700 /run/user/1000
RUN cmp /toolchain-packages.txt /expected-packages.txt
USER 1000:1000
ENV RUNNER_TEMP=/work/runner-temp TMPDIR=/work/runner-temp PYTHONDONTWRITEBYTECODE=1
WORKDIR /work/tests
