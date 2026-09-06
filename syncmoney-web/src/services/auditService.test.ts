import { describe, it, expect } from 'vitest'
import { exportAuditCSV } from './auditService'
import type { AuditRecord } from '@/api/types'

describe('auditService - exportAuditCSV', () => {
  it('should escape CSV Formula Injection characters (=, +, -, @, \\t, \\r)', () => {
    const maliciousRecords: AuditRecord[] = [
      {
        id: '1',
        playerUuid: 'uuid-1',
        playerName: '=cmd|"/C calc"!A0',
        type: 'SET_BALANCE',
        amount: '+1000',
        balanceBefore: '0',
        balanceAfter: '-500',
        source: '@evil_source',
        serverName: '\tSurvival',
        timestamp: 1700000000000,
      },
      {
        id: '2',
        playerUuid: 'uuid-2',
        playerName: 'NormalPlayer',
        type: 'TRANSFER',
        amount: '50.00',
        balanceBefore: '100.00',
        balanceAfter: '50.00',
        source: 'COMMAND',
        serverName: 'Lobby',
        timestamp: 1700000001000,
      }
    ]

    const csv = exportAuditCSV(maliciousRecords)
    const lines = csv.split('\n')

    expect(lines[0]).toBe('Time,Player,Type,Amount,Balance After,Source,Server')

    // First record should have sanitizing single quote prepended
    expect(lines[1]).toContain(`"'=cmd|""/C calc""!A0"`)
    expect(lines[1]).toContain(`"'+1000"`)
    expect(lines[1]).toContain(`"'-500"`)
    expect(lines[1]).toContain(`"'@evil_source"`)
    expect(lines[1]).toContain(`"'\tSurvival"`)

    // Normal record should NOT have prepended quote
    expect(lines[2]).toContain(`"NormalPlayer"`)
    expect(lines[2]).toContain(`"50.00"`)
  })
})
