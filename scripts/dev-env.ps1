# 本项目的可移动本机构建环境；不修改系统 PATH。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolchainRoot = Join-Path $projectRoot '.tools/toolchain'
if (-not (Test-Path -LiteralPath (Join-Path $toolchainRoot 'env.ps1'))) {
    throw "缺少本地工具链：$toolchainRoot"
}
. (Join-Path $toolchainRoot 'env.ps1')
