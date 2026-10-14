import assert from "node:assert/strict";
import test from "node:test";

import { resolveMineflayerUuid } from "./m10-online-client-identity.mjs";

test("resolves the protocol UUID before Mineflayer player/entity state is populated", () => {
  const expected = "1ef97e85-b60f-3c2d-8fca-45edad0b7102";
  const bot = {
    player: undefined,
    entity: { uuid: undefined },
    _client: { uuid: expected },
  };

  assert.equal(resolveMineflayerUuid(bot), expected);
});

test("prefers Mineflayer player state and rejects malformed UUIDs", () => {
  const expected = "1ef97e85-b60f-3c2d-8fca-45edad0b7102";

  assert.equal(
    resolveMineflayerUuid({ player: { uuid: expected }, _client: { uuid: "invalid" } }),
    expected,
  );
  assert.equal(resolveMineflayerUuid({ player: { uuid: "not-a-uuid" } }), null);
});
