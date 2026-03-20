import type { AuditRecord } from '@/api/types'

export const auditFixtures: AuditRecord[] = [
  {
    id: '1',
    playerUuid: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    playerName: 'Steve',
    type: 'DEPOSIT',
    amount: '1000',
    balanceBefore: '5000',
    balanceAfter: '6000',
    source: 'command',
    reason: 'Daily bonus',
    timestamp: Date.now() - 3600000,
    serverName: 'Server-1'
  },
  {
    id: '2',
    playerUuid: 'b2c3d4e5-f6a7-8901-bcde-f23456789012',
    playerName: 'Alex',
    type: 'WITHDRAW',
    amount: '-500',
    balanceBefore: '3000',
    balanceAfter: '2500',
    source: 'command',
    timestamp: Date.now() - 7200000,
    serverName: 'Server-1'
  },
  {
    id: '3',
    playerUuid: 'c3d4e5f6-a7b8-9012-cdef-345678901234',
    playerName: 'Herobrine',
    type: 'TRANSFER',
    amount: '-1000',
    balanceBefore: '10000',
    balanceAfter: '9000',
    source: 'pay',
    targetName: 'Steve',
    timestamp: Date.now() - 10800000,
    serverName: 'Server-2'
  },
  {
    id: '4',
    playerUuid: 'd4e5f6a7-b8c9-0123-defg-456789012345',
    playerName: 'Notch',
    type: 'SET_BALANCE',
    amount: '50000',
    balanceBefore: '0',
    balanceAfter: '50000',
    source: 'admin',
    reason: 'Initial balance',
    timestamp: Date.now() - 14400000,
    serverName: 'Server-1'
  },
  {
    id: '5',
    playerUuid: 'e5f6a7b8-c9d0-1234-efgh-567890123456',
    playerName: 'Creeper',
    type: 'DEPOSIT',
    amount: '250',
    balanceBefore: '750',
    balanceAfter: '1000',
    source: 'economy.earn',
    reason: 'Mob kill reward',
    timestamp: Date.now() - 18000000,
    serverName: 'Server-1'
  }
]
