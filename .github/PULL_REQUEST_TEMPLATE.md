## Summary

Describe the problem and the resulting behavior after this change.

## Validation

List the checks you actually ran. Include real-server or integration testing when the change affects scheduler behavior, storage, lifecycle, CMI, cross-server synchronization, or server compatibility.

## Checklist

- [ ] I kept blocking I/O out of Vault hot paths, placeholders, tab completion, entity tasks, and player-message paths.
- [ ] I preserved money normalization, insufficient-funds behavior, accepted-write durability, and monotonic version rules where applicable.
- [ ] I used the correct Paper/Folia scheduler ownership for player and entity work where applicable.
- [ ] I updated English and Traditional Chinese documentation together when public behavior or APIs changed.
- [ ] I rebuilt the embedded Web Admin bundle with `pnpm run build:embedded` when frontend source or public frontend metadata changed.
- [ ] I removed credentials, local runtime state, databases, logs, worlds, and other private development material from the diff.
