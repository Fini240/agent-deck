FROM node:22-bookworm-slim
ARG CODEX_VERSION=0.160.1
ARG CLAUDE_VERSION=2.1.283
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv tmux procps lsof git ca-certificates curl && rm -rf /var/lib/apt/lists/* \
 && npm install -g @openai/codex@${CODEX_VERSION} @anthropic-ai/claude-code@${CLAUDE_VERSION} \
 && python3 -m venv /opt/venv
COPY server /opt/agentdeck/server
RUN /opt/venv/bin/pip install --no-cache-dir /opt/agentdeck/server
RUN useradd --uid 1001 --create-home --home-dir /home/agentdeck agentdeck
ENV SHELL=/bin/bash PATH="/opt/venv/bin:${PATH}" HOME=/home/agentdeck AGENTDECK_HOME=/home/agentdeck/.agent-deck CODEX_HOME=/home/agentdeck/.codex CLAUDE_CONFIG_DIR=/home/agentdeck/.claude
USER 1001:1001
WORKDIR /workspace
CMD ["agentdeck-server"]
