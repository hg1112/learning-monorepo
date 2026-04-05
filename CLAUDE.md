# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A personal learning monorepo for projects, experiments, and coding challenges across different technologies. No shared build tooling — each project is self-contained and uses whatever stack it needs.

## Directory Structure

```
apps/         # Full applications — web apps, APIs, CLIs
experiments/  # Quick explorations and proof-of-concepts
challenges/   # Coding challenges (LeetCode, system design, etc.)
shared/       # Reusable code shared across projects
docs/         # Notes, write-ups, learnings
```

Each project lives in its own subdirectory and is fully independent. Follow the conventions of the language/framework used within that project.

## Editor

**Neovim** with [kickstart.nvim](https://github.com/nvim-lua/kickstart.nvim) — single `init.lua` config at `~/.config/nvim/init.lua`.

- AI assistance: `codecompanion.nvim` (Claude + Gemini via API)
- Claude Code CLI and Gemini CLI: run via `:term` inside Neovim
- Jupyter notebooks: `jupyter lab` in WSL → open `localhost:8888` in browser

## Neovim Setup (WSL)

```bash
# Install Neovim latest
curl -LO https://github.com/neovim/neovim/releases/latest/download/nvim-linux-x86_64.tar.gz
tar xf nvim-linux-x86_64.tar.gz && sudo mv nvim-linux-x86_64 /opt/nvim
echo 'export PATH=$PATH:/opt/nvim/bin' >> ~/.bashrc && source ~/.bashrc

# Install kickstart.nvim
git clone https://github.com/nvim-lua/kickstart.nvim.git ~/.config/nvim
nvim  # auto-installs plugins on first launch
```
