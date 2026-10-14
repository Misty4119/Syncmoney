const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function resolveMineflayerUuid(bot) {
  const candidates = [bot.player?.uuid, bot.entity?.uuid, bot._client?.uuid];
  return candidates.find((uuid) => typeof uuid === "string" && UUID_PATTERN.test(uuid)) ?? null;
}
