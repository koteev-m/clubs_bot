# TARGET identity — synthetic reference report

**PROPOSED; artificial values only; zero DB/API/network writes.**
Actual HMAC operations use public synthetic keys. No Kotlin/runtime/SQL-lock proof is claimed.
[Technical contract](../product/ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md).

## owner-appoints-club-manager

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "client.club.calendar.read": [
        "ROLE:target-i1"
      ],
      "client.club.calendar.write": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.entry_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.finance_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.manager": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.promoter": [
        "ROLE:target-i1"
      ],
      "client.staff.delegate.calendar": [
        "ROLE:target-i1"
      ],
      "client.staff.disable.club": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.entry_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.finance_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.manager": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.promoter": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "client.club.calendar.read",
        "client.club.calendar.write",
        "client.staff.assign.entry_manager",
        "client.staff.assign.finance_manager",
        "client.staff.assign.manager",
        "client.staff.assign.promoter",
        "client.staff.delegate.calendar",
        "client.staff.disable.club",
        "client.staff.revoke.entry_manager",
        "client.staff.revoke.finance_manager",
        "client.staff.revoke.manager",
        "client.staff.revoke.promoter"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## owner-appoints-network-manager-exact-club

```json
{
  "CURRENT": {
    "c1/A/7": {},
    "c1/B/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "client.staff.assign.club_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.club_manager": [
        "ROLE:target-i1"
      ]
    },
    "c1/B/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "client.staff.assign.club_manager",
        "client.staff.revoke.club_manager"
      ],
      "removed": []
    },
    "c1/B/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## network-manager-explicit-club

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "client.club.calendar.read": [
        "ROLE:target-i1"
      ],
      "client.club.calendar.write": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.entry_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.finance_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.manager": [
        "ROLE:target-i1"
      ],
      "client.staff.assign.promoter": [
        "ROLE:target-i1"
      ],
      "client.staff.delegate.calendar": [
        "ROLE:target-i1"
      ],
      "client.staff.disable.club": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.entry_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.finance_manager": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.manager": [
        "ROLE:target-i1"
      ],
      "client.staff.revoke.promoter": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "client.club.calendar.read",
        "client.club.calendar.write",
        "client.staff.assign.entry_manager",
        "client.staff.assign.finance_manager",
        "client.staff.assign.manager",
        "client.staff.assign.promoter",
        "client.staff.delegate.calendar",
        "client.staff.disable.club",
        "client.staff.revoke.entry_manager",
        "client.staff.revoke.finance_manager",
        "client.staff.revoke.manager",
        "client.staff.revoke.promoter"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## network-manager-denied-neighbor-club

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## club-manager-appoints-promoter

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "booking.promoter.create": [
        "ROLE:target-i1"
      ],
      "booking.promoter.own.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "booking.promoter.create",
        "booking.promoter.own.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## promoter-three-of-five-no-client-inheritance

```json
{
  "CURRENT": {
    "c1/A/7": {},
    "c1/B/7": {},
    "c1/C/7": {},
    "c1/D/7": {},
    "c1/E/7": {},
    "c2/Z/7": {
      "booking.promoter.create": [
        "ROLE:foreign7"
      ],
      "booking.promoter.own.read": [
        "ROLE:foreign7"
      ]
    }
  },
  "TARGET": {
    "c1/A/7": {
      "booking.promoter.create": [
        "ROLE:target-A"
      ],
      "booking.promoter.own.read": [
        "ROLE:target-A"
      ]
    },
    "c1/B/7": {
      "booking.promoter.create": [
        "ROLE:target-B"
      ],
      "booking.promoter.own.read": [
        "ROLE:target-B"
      ]
    },
    "c1/C/7": {
      "booking.promoter.create": [
        "ROLE:target-C"
      ],
      "booking.promoter.own.read": [
        "ROLE:target-C"
      ]
    },
    "c1/D/7": {},
    "c1/E/7": {},
    "c2/Z/7": {
      "booking.promoter.create": [
        "ROLE:foreign7"
      ],
      "booking.promoter.own.read": [
        "ROLE:foreign7"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "booking.promoter.create",
        "booking.promoter.own.read"
      ],
      "removed": []
    },
    "c1/B/7": {
      "added": [
        "booking.promoter.create",
        "booking.promoter.own.read"
      ],
      "removed": []
    },
    "c1/C/7": {
      "added": [
        "booking.promoter.create",
        "booking.promoter.own.read"
      ],
      "removed": []
    },
    "c1/D/7": {
      "added": [],
      "removed": []
    },
    "c1/E/7": {
      "added": [],
      "removed": []
    },
    "c2/Z/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "A": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-A"
    },
    "B": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-B"
    },
    "C": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 3,
      "source": "target-C"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    },
    "c1/B/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    },
    "c1/C/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-A": "ACTIVE",
    "target-B": "ACTIVE",
    "target-C": "ACTIVE"
  }
}
```
</details>

## unconfirmed-recipient-has-no-target-permissions

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## wrong-account-needs-reissue

```json
{
  "CURRENT": {
    "c1/A/7": {},
    "c1/A/8": {}
  },
  "TARGET": {
    "c1/A/7": {},
    "c1/A/8": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    },
    "c1/A/8": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EXPLICIT_CONFIRMATION_REQUIRED",
    "SECRET_CONSUMED_OR_INVALID"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 8,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## stale-signed-initdata

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "IDENTITY_EXPIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## future-initdata

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "IDENTITY_EXPIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## wrong-recipient-bot-audience

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "AUDIENCE_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## unknown-bot-audience

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "AUDIENCE_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## hmac-covers-signature

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "IDENTITY_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## duplicate-signed-keys-rejected

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "DUPLICATE_KEY"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## tampered-intent-envelope

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "ENVELOPE_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## unclaimed-before-full-disable

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "INVITATION_FENCED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "pending": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 2,
      "source": "target-pending"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 2,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## claimed-before-issuer-revoke

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/3": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "cmA": "REVOKED"
  }
}
```
</details>

## same-key-claim-confirm-current-replay

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "REPLAY_CURRENT",
    "COMMITTED",
    "REPLAY_CURRENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## distinct-key-reused-secret-denied

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "SECRET_CONSUMED_OR_INVALID"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## two-confirm-distinct-cas

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "VERSION_CONFLICT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## confirm-first-full-disable

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## full-disable-first-confirm

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "INVITATION_FENCED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "pending": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-pending"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 2,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## claim-first-invitation-revoke

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "REVOKED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## invitation-revoke-first-claim

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "INVITATION_TERMINAL_OR_EXPIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "REVOKED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## issue-first-issuer-revoke

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "EVENT",
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/3": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "cmA": "REVOKED"
  }
}
```
</details>

## issuer-revoke-first-issue

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "EVENT",
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {
    "c1/A/3": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "cmA": "REVOKED"
  }
}
```
</details>

## confirm-first-platform-membership-revoke

```json
{
  "CURRENT": {
    "c1/A/7": {},
    "c2/Z/7": {
      "booking.promoter.create": [
        "ROLE:foreign7"
      ],
      "booking.promoter.own.read": [
        "ROLE:foreign7"
      ]
    }
  },
  "TARGET": {
    "c1/A/7": {},
    "c2/Z/7": {
      "booking.promoter.create": [
        "ROLE:foreign7"
      ],
      "booking.promoter.own.read": [
        "ROLE:foreign7"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    },
    "c2/Z/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {
    "c1/7": {
      "active": false,
      "version": 2,
      "floor": 1
    }
  },
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## platform-membership-revoke-first-confirm

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "INVITATION_FENCED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {
    "c1/7": {
      "active": false,
      "version": 2,
      "floor": 1
    }
  },
  "source_changes": {}
}
```
</details>

## policy-retire-first-confirm

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "POLICY_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## confirm-first-policy-retire

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## new-policy-version-does-not-upgrade-pin

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## invitation-expires-during-lock-wait

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "EVENT",
    "COMMITTED",
    "INVITATION_TERMINAL_OR_EXPIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## claim-attestation-expires

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "RECIPIENT_PROOF_EXPIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## same-account-refresh-is-not-rebinding

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## explicit-readmission-new-source-old-tombstones

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-readmit"
      ],
      "night.read": [
        "ROLE:target-readmit"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "EVENT",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "readmit": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-readmit"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 3,
      "version": 5,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED",
    "target-readmit": "ACTIVE"
  }
}
```
</details>

## readmission-requires-exact-regrant-ack

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "COMMITTED",
    "COMMITTED",
    "REGRANT_ACK_REQUIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "readmit": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-readmit"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## membership-restore-is-not-staff-regrant

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "EVENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {
    "c1/7": {
      "active": true,
      "version": 3,
      "floor": 1
    }
  },
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## unclaimed-other-club-without-admission-after-platform-restore

```json
{
  "CURRENT": {
    "c1/B/7": {},
    "c2/Z/7": {
      "booking.promoter.create": [
        "ROLE:foreign7"
      ],
      "booking.promoter.own.read": [
        "ROLE:foreign7"
      ]
    }
  },
  "TARGET": {
    "c1/B/7": {},
    "c2/Z/7": {
      "booking.promoter.create": [
        "ROLE:foreign7"
      ],
      "booking.promoter.own.read": [
        "ROLE:foreign7"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/B/7": {
      "added": [],
      "removed": []
    },
    "c2/Z/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "EVENT",
    "EVENT",
    "INVITATION_FENCED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {
    "c1/7": {
      "active": true,
      "version": 3,
      "floor": 1
    }
  },
  "source_changes": {}
}
```
</details>

## old-receipt-after-new-epoch

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-readmit"
      ],
      "night.read": [
        "ROLE:target-readmit"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "REPLAY_CURRENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "readmit": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-readmit"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 3,
      "version": 5,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED",
    "target-readmit": "ACTIVE"
  }
}
```
</details>

## additional-role-then-revoke-one-source

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "entrance.arrival.record": [
        "ROLE:target-entry"
      ],
      "night.read": [
        "ROLE:target-entry"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "entrance.arrival.record",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "entry": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-entry"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 4,
      "floor": 2,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED",
    "target-entry": "ACTIVE"
  }
}
```
</details>

## delegation-is-separate-from-role-invitation

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "client.club.calendar.read": [
        "DELEGATION:calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:calendar"
      ],
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "client.club.calendar.read",
        "client.club.calendar.write",
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## self-escalation-denied

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "PROTECTED_SUBJECT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## legacy-global-is-not-business-ownership

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## ownership-lost-before-confirm

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## inactive-membership-is-not-auto-restored

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "EVENT",
    "COMMITTED",
    "MEMBERSHIP_INACTIVE"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {
    "c1/7": {
      "active": false,
      "version": 2,
      "floor": 0
    }
  },
  "source_changes": {}
}
```
</details>

## cross-client-issuer-denied

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "ISSUER_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## terminal-source-id-reuse-denied

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "COMMITTED",
    "COMMITTED",
    "SOURCE_ID_TERMINAL"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "readmit": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 1,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## claimed-refresh-cannot-cross-revocation

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "INVITATION_FENCED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    },
    "pending": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-pending"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "DISABLED",
      "epoch": 2,
      "version": 3,
      "floor": 2,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "REVOKED"
  }
}
```
</details>

## reissue-rotates-id-seq-and-secret

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i2"
      ],
      "night.read": [
        "ROLE:target-i2"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "SECRET_CONSUMED_OR_INVALID",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "REVOKED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    },
    "i2": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 2,
      "source": "target-i2"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i2": "ACTIVE"
  }
}
```
</details>

## unknown-authoritative-frontier-denied

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "FRONTIER_UNKNOWN"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## unknown-protected-subject-denied

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "PROTECTION_UNKNOWN"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## late-audit-failure-no-partial-issue

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "AUDIT_FAILURE_ROLLBACK",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## read-only-unknown-result-never-executes

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "NOT_COMMITTED_AT_SNAPSHOT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## old-envelope-cannot-use-new-key

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "INTENT_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## identity-expires-during-lock-wait

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "IDENTITY_EXPIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## command-challenge-expires-during-lock-wait

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "CHALLENGE_EXPIRED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "ISSUED",
      "claimant": null,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## body-userid-cannot-select-recipient

```json
{
  "CURRENT": {
    "c1/A/7": {},
    "c1/A/99": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    },
    "c1/A/99": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    },
    "c1/A/99": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## fresh-first-assignment-creates-missing-member-only-on-confirm

```json
{
  "CURRENT": {
    "c1/A/8": {}
  },
  "TARGET": {
    "c1/A/8": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/8": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 8,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/8": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {
    "c1/8": {
      "active": true,
      "version": 1,
      "floor": 0
    }
  },
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## business-owner-cannot-be-staff-recipient

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "PROTECTED_SUBJECT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## platform-owner-cannot-be-staff-recipient

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "PROTECTED_SUBJECT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## business-owner-guest-are-not-staff-role-codes

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "ROLE_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## finance-exact-shift-no-neighbor-club

```json
{
  "CURRENT": {
    "c1/A/7": {},
    "c1/B/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "finance.report.read": [
        "ROLE:target-i1"
      ]
    },
    "c1/B/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "finance.report.read"
      ],
      "removed": []
    },
    "c1/B/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## finance-denies-other-shift

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## finance-requires-explicit-selector

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "SELECTOR_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {},
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## confirm-first-issuer-revoke-keeps-independent-role

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED",
    "EVENT"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/3": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 1,
      "member_version": 1
    },
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "cmA": "REVOKED",
    "target-i1": "ACTIVE"
  }
}
```
</details>

## canonical-issuer-and-approved-branded-recipient

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {
      "floor.tables.read": [
        "ROLE:target-i1"
      ],
      "night.read": [
        "ROLE:target-i1"
      ]
    }
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [
        "floor.tables.read",
        "night.read"
      ],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "COMMITTED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CONSUMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {
    "c1/A/7": {
      "status": "ACTIVE",
      "epoch": 1,
      "version": 2,
      "floor": 0,
      "member_version": 1
    }
  },
  "membership_changes": {},
  "source_changes": {
    "target-i1": "ACTIVE"
  }
}
```
</details>

## branded-audience-revoked-after-claim

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "EVENT",
    "AUDIENCE_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>

## confirm-cannot-switch-issuer-bot

```json
{
  "CURRENT": {
    "c1/A/7": {}
  },
  "TARGET": {
    "c1/A/7": {}
  },
  "DIFFERENCE": {
    "c1/A/7": {
      "added": [],
      "removed": []
    }
  },
  "RISK": "Fail closed; no automatic permission or scope inheritance.",
  "outcomes": [
    "COMMITTED",
    "COMMITTED",
    "AUDIENCE_DENIED"
  ],
  "denials_atomic": true,
  "external_writes": 0
}
```

<details><summary>Synthetic control state</summary>

```json
{
  "invitations": {
    "i1": {
      "status": "CLAIMED",
      "claimant": 7,
      "seq": 1,
      "source": "target-i1"
    }
  },
  "admission_changes": {},
  "membership_changes": {},
  "source_changes": {}
}
```
</details>
