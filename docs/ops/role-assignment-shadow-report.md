# Role assignment contract — synthetic reference

**Reference policies remain PROPOSED; architecture is ACCEPTED for inactive local foundation. CURRENT/TARGET are hypothetical states; zero DB/API/external writes.**

Source revision is the base; checksums bind the local unpublished overlay. Previous baselines are preserved.
Interleavings model serial outcomes and CAS conflicts; they are not PostgreSQL lock/concurrency tests.
Actual disposable DB evidence and implementation limits: [foundation report](role-assignment-foundation.md).

## one-role

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "one-role",
  "input_unchanged": true,
  "outcomes": [],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## two-independent-roles

```json
{
  "CURRENT": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "two-independent-roles",
  "input_unchanged": true,
  "outcomes": [],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## role-plus-delegation

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-calendar"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-calendar"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "role-plus-delegation",
  "input_unchanged": true,
  "outcomes": [],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## two-role-sources-revoke-one-permission-remains

```json
{
  "CURRENT": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "tables.read",
        "tables.seat"
      ],
      "source_changes": {
        "night.read": {
          "after": [
            "ROLE:b-entry"
          ],
          "before": [
            "ROLE:a-manager",
            "ROLE:b-entry"
          ]
        },
        "tables.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:b-entry"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "two-role-sources-revoke-one-permission-remains",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## revoke-second-role-keeps-first

```json
{
  "CURRENT": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "entry.scan"
      ],
      "source_changes": {
        "entry.scan": {
          "after": [],
          "before": [
            "ROLE:b-entry"
          ]
        },
        "night.read": {
          "after": [
            "ROLE:a-manager"
          ],
          "before": [
            "ROLE:a-manager",
            "ROLE:b-entry"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "revoke-second-role-keeps-first",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## revoke-only-delegation

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-calendar"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "client.club.calendar.read",
        "client.club.calendar.write"
      ],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "client.club.calendar.write": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "revoke-only-delegation",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## two-delegation-sources-revoke-one-keeps-permission

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-one",
        "DELEGATION:d-two"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-one",
        "DELEGATION:d-two"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [
            "DELEGATION:d-two"
          ],
          "before": [
            "DELEGATION:d-one",
            "DELEGATION:d-two"
          ]
        },
        "client.club.calendar.write": {
          "after": [
            "DELEGATION:d-two"
          ],
          "before": [
            "DELEGATION:d-one",
            "DELEGATION:d-two"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-two"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-two"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "two-delegation-sources-revoke-one-keeps-permission",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## full-disable-keeps-other-client

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-calendar"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    },
    "102/2001/7": {
      "finance.report.read": [
        "ROLE:other-client-fin"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "client.club.calendar.read",
        "client.club.calendar.write",
        "night.read",
        "tables.read",
        "tables.seat"
      ],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "client.club.calendar.write": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "night.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    },
    "102/2001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {},
    "102/2001/7": {
      "finance.report.read": [
        "ROLE:other-client-fin"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": false,
        "epoch": 2,
        "mode": "TARGET",
        "revoked_through_invitation_id": 1000,
        "version": 2
      }
    },
    "102/2001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "full-disable-keeps-other-client",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## fresh-readmission-never-reactivates-old-ids

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-calendar"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "client.club.calendar.read",
        "client.club.calendar.write"
      ],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "client.club.calendar.write": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "night.read": {
          "after": [
            "ROLE:new-manager"
          ],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.read": {
          "after": [
            "ROLE:new-manager"
          ],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [
            "ROLE:new-manager"
          ],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:new-manager"
      ],
      "tables.read": [
        "ROLE:new-manager"
      ],
      "tables.seat": [
        "ROLE:new-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 3,
        "mode": "TARGET",
        "revoked_through_invitation_id": 1000,
        "version": 3
      }
    }
  },
  "external_writes": 0,
  "id": "fresh-readmission-never-reactivates-old-ids",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## revoked-grant-needs-new-explicit-ack

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "EXPLICIT_REGRANT_ACK_REQUIRED"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "revoked-grant-needs-new-explicit-ack",
  "input_unchanged": true,
  "outcomes": [
    "EXPLICIT_REGRANT_ACK_REQUIRED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## explicit-regrant-new-source-old-tombstone-stays-revoked

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [
        "client.club.calendar.read",
        "client.club.calendar.write"
      ],
      "removed": [],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [
            "DELEGATION:d-new"
          ],
          "before": []
        },
        "client.club.calendar.write": {
          "after": [
            "DELEGATION:d-new"
          ],
          "before": []
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-new"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-new"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "explicit-regrant-new-source-old-tombstone-stays-revoked",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## inactive-membership-no-target-authority

```json
{
  "CURRENT": {
    "101/1001/7": {}
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "TARGET_MEMBERSHIP_OR_MAPPING_DENIED"
  ],
  "TARGET": {
    "101/1001/7": {}
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "inactive-membership-no-target-authority",
  "input_unchanged": true,
  "outcomes": [
    "TARGET_MEMBERSHIP_OR_MAPPING_DENIED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## role-change-after-concurrent-revoke-fails

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "night.read",
        "tables.read",
        "tables.seat"
      ],
      "source_changes": {
        "night.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [
    "STALE_FENCE_CONFLICT"
  ],
  "TARGET": {
    "101/1001/7": {}
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "role-change-after-concurrent-revoke-fails",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "STALE_FENCE_CONFLICT"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## revoke-stale-source-after-role-change-keeps-new-source

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [
        "entry.scan"
      ],
      "removed": [
        "tables.read",
        "tables.seat"
      ],
      "source_changes": {
        "entry.scan": {
          "after": [
            "ROLE:new-entry"
          ],
          "before": []
        },
        "night.read": {
          "after": [
            "ROLE:new-entry"
          ],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [
    "STALE_FENCE_CONFLICT"
  ],
  "TARGET": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:new-entry"
      ],
      "night.read": [
        "ROLE:new-entry"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "revoke-stale-source-after-role-change-keeps-new-source",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "STALE_FENCE_CONFLICT"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## two-concurrent-role-grants-conflict-then-fresh-retry

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [
        "entry.scan",
        "finance.report.read"
      ],
      "removed": [],
      "source_changes": {
        "entry.scan": {
          "after": [
            "ROLE:b-entry"
          ],
          "before": []
        },
        "finance.report.read": {
          "after": [
            "ROLE:c-fin"
          ],
          "before": []
        },
        "night.read": {
          "after": [
            "ROLE:a-manager",
            "ROLE:b-entry"
          ],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [
    "STALE_FENCE_CONFLICT"
  ],
  "TARGET": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "finance.report.read": [
        "ROLE:c-fin"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 3
      }
    }
  },
  "external_writes": 0,
  "id": "two-concurrent-role-grants-conflict-then-fresh-retry",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "STALE_FENCE_CONFLICT",
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## promoter-three-of-five-clubs

```json
{
  "CURRENT": {
    "101/1001/10": {},
    "101/1002/10": {},
    "101/1003/10": {},
    "101/1004/10": {},
    "101/1005/10": {}
  },
  "DIFFERENCE": {
    "101/1001/10": {
      "added": [
        "booking.promoter.create",
        "booking.promoter.own.read"
      ],
      "removed": [],
      "source_changes": {
        "booking.promoter.create": {
          "after": [
            "ROLE:prom-1001"
          ],
          "before": []
        },
        "booking.promoter.own.read": {
          "after": [
            "ROLE:prom-1001"
          ],
          "before": []
        }
      }
    },
    "101/1002/10": {
      "added": [
        "booking.promoter.create",
        "booking.promoter.own.read"
      ],
      "removed": [],
      "source_changes": {
        "booking.promoter.create": {
          "after": [
            "ROLE:prom-1002"
          ],
          "before": []
        },
        "booking.promoter.own.read": {
          "after": [
            "ROLE:prom-1002"
          ],
          "before": []
        }
      }
    },
    "101/1003/10": {
      "added": [
        "booking.promoter.create",
        "booking.promoter.own.read"
      ],
      "removed": [],
      "source_changes": {
        "booking.promoter.create": {
          "after": [
            "ROLE:prom-1003"
          ],
          "before": []
        },
        "booking.promoter.own.read": {
          "after": [
            "ROLE:prom-1003"
          ],
          "before": []
        }
      }
    },
    "101/1004/10": {
      "added": [],
      "removed": [],
      "source_changes": {}
    },
    "101/1005/10": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/10": {
      "booking.promoter.create": [
        "ROLE:prom-1001"
      ],
      "booking.promoter.own.read": [
        "ROLE:prom-1001"
      ]
    },
    "101/1002/10": {
      "booking.promoter.create": [
        "ROLE:prom-1002"
      ],
      "booking.promoter.own.read": [
        "ROLE:prom-1002"
      ]
    },
    "101/1003/10": {
      "booking.promoter.create": [
        "ROLE:prom-1003"
      ],
      "booking.promoter.own.read": [
        "ROLE:prom-1003"
      ]
    },
    "101/1004/10": {},
    "101/1005/10": {}
  },
  "authority_revisions": {
    "101/1001/10": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    },
    "101/1002/10": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    },
    "101/1003/10": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    },
    "101/1004/10": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    },
    "101/1005/10": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "promoter-three-of-five-clubs",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "COMMITTED_IN_MODEL_ONLY",
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## manager-calendar-write-separate-delegation

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [
        "client.club.calendar.read",
        "client.club.calendar.write"
      ],
      "removed": [],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [
            "DELEGATION:d-new"
          ],
          "before": []
        },
        "client.club.calendar.write": {
          "after": [
            "DELEGATION:d-new"
          ],
          "before": []
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-new"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-new"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "manager-calendar-write-separate-delegation",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## financial-read-does-not-cross-club

```json
{
  "CURRENT": {
    "101/1001/7": {
      "finance.report.read": [
        "ROLE:fin-a"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    },
    "101/1002/7": {},
    "102/2001/7": {
      "finance.report.read": [
        "ROLE:other-client-fin"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    },
    "101/1002/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    },
    "102/2001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "finance.report.read": [
        "ROLE:fin-a"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    },
    "101/1002/7": {},
    "102/2001/7": {
      "finance.report.read": [
        "ROLE:other-client-fin"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    },
    "101/1002/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    },
    "102/2001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "financial-read-does-not-cross-club",
  "input_unchanged": true,
  "outcomes": [],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## audit-failure-rolls-back-source-and-receipt

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "AUDIT_FAILURE_ROLLBACK"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "audit-failure-rolls-back-source-and-receipt",
  "input_unchanged": true,
  "outcomes": [
    "AUDIT_FAILURE_ROLLBACK"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## receipt-failure-rolls-back-source-and-audit

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "RECEIPT_FAILURE_ROLLBACK"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "receipt-failure-rolls-back-source-and-audit",
  "input_unchanged": true,
  "outcomes": [
    "RECEIPT_FAILURE_ROLLBACK"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## legacy-writer-on-mixed-assignments-fails-closed

```json
{
  "CURRENT": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "LEGACY_WRITER_BLOCKED_IN_TARGET"
  ],
  "TARGET": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "legacy-writer-on-mixed-assignments-fails-closed",
  "input_unchanged": true,
  "outcomes": [
    "LEGACY_WRITER_BLOCKED_IN_TARGET"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## old-command-replay-does-not-resurrect-revoked-source

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 3
      }
    }
  },
  "external_writes": 0,
  "id": "old-command-replay-does-not-resurrect-revoked-source",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "COMMITTED_IN_MODEL_ONLY",
    "CURRENT_STATE_REPLAY_NO_EXECUTION"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## replay-after-issuer-loses-authority-denied

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [
        "entry.scan"
      ],
      "removed": [],
      "source_changes": {
        "entry.scan": {
          "after": [
            "ROLE:b-entry"
          ],
          "before": []
        },
        "night.read": {
          "after": [
            "ROLE:a-manager",
            "ROLE:b-entry"
          ],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [
    "CURRENT_MANAGEMENT_AUTHORITY_DENIED"
  ],
  "TARGET": {
    "101/1001/7": {
      "entry.scan": [
        "ROLE:b-entry"
      ],
      "night.read": [
        "ROLE:a-manager",
        "ROLE:b-entry"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "replay-after-issuer-loses-authority-denied",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "COMMITTED_IN_MODEL_ONLY",
    "CURRENT_MANAGEMENT_AUTHORITY_DENIED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## platform-revoke-restore-membership-only

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-calendar"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    },
    "102/2001/7": {
      "finance.report.read": [
        "ROLE:other-client-fin"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "client.club.calendar.read",
        "client.club.calendar.write",
        "night.read",
        "tables.read",
        "tables.seat"
      ],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "client.club.calendar.write": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "night.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    },
    "102/2001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {},
    "102/2001/7": {
      "finance.report.read": [
        "ROLE:other-client-fin"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": false,
        "epoch": 2,
        "mode": "TARGET",
        "revoked_through_invitation_id": 1000,
        "version": 3
      }
    },
    "102/2001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "platform-revoke-restore-membership-only",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## old-staff-invitation-cannot-readmit

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "night.read",
        "tables.read",
        "tables.seat"
      ],
      "source_changes": {
        "night.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [
    "NEW_TARGET_ADMISSION_REQUIRED"
  ],
  "TARGET": {
    "101/1001/7": {}
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": false,
        "epoch": 2,
        "mode": "TARGET",
        "revoked_through_invitation_id": 1000,
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "old-staff-invitation-cannot-readmit",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "NEW_TARGET_ADMISSION_REQUIRED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## terminal-source-id-cannot-be-reused

```json
{
  "CURRENT": {
    "101/1001/7": {}
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "SOURCE_ID_TERMINAL_OR_EXISTS"
  ],
  "TARGET": {
    "101/1001/7": {}
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "terminal-source-id-cannot-be-reused",
  "input_unchanged": true,
  "outcomes": [
    "SOURCE_ID_TERMINAL_OR_EXISTS"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## manager-self-escalation-denied

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "CURRENT_MANAGEMENT_AUTHORITY_DENIED"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "manager-self-escalation-denied",
  "input_unchanged": true,
  "outcomes": [
    "CURRENT_MANAGEMENT_AUTHORITY_DENIED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## foreign-source-id-cannot-be-revoked

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "EXACT_SOURCE_OWNERSHIP_REQUIRED"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "foreign-source-id-cannot-be-revoked",
  "input_unchanged": true,
  "outcomes": [
    "EXACT_SOURCE_OWNERSHIP_REQUIRED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## policy-version-drift-does-not-expand-existing-assignments

```json
{
  "CURRENT": {
    "101/1001/7": {}
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "POLICY_HASH_DRIFT_FAIL_CLOSED"
  ],
  "TARGET": {
    "101/1001/7": {}
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "policy-version-drift-does-not-expand-existing-assignments",
  "input_unchanged": true,
  "outcomes": [],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## empty-financial-shift-set-is-not-unlimited

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "EMPTY_SELECTOR_FAIL_CLOSED"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "empty-financial-shift-set-is-not-unlimited",
  "input_unchanged": true,
  "outcomes": [],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## release-rollback-preview-after-revoke-does-not-restore

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "night.read",
        "tables.read",
        "tables.seat"
      ],
      "source_changes": {
        "night.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.read": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        },
        "tables.seat": {
          "after": [],
          "before": [
            "ROLE:a-manager"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {}
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "release-rollback-preview-after-revoke-does-not-restore",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY",
    "NO_EXECUTION_NO_RESURRECTION"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## ambiguous-active-compat-source-blocks-target-writer

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "AMBIGUOUS_COMPAT_SOURCE_BLOCKS_TARGET"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "ambiguous-active-compat-source-blocks-target-writer",
  "input_unchanged": true,
  "outcomes": [
    "AMBIGUOUS_COMPAT_SOURCE_BLOCKS_TARGET"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## delegation-cannot-grant-management-role

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "DELEGATION_AUTHORITY_DENIED"
  ],
  "TARGET": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "delegation-cannot-grant-management-role",
  "input_unchanged": true,
  "outcomes": [
    "DELEGATION_AUTHORITY_DENIED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## owner-assigns-club-manager-without-execution-config-grants

```json
{
  "CURRENT": {
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [
        "client.club.calendar.read",
        "client.club.calendar.write",
        "client.club.profile.read",
        "client.club.profile.write",
        "client.staff.assign.entry_manager",
        "client.staff.assign.finance_manager",
        "client.staff.assign.manager",
        "client.staff.assign.promoter",
        "client.staff.delegate.calendar",
        "client.staff.delegate.profile",
        "client.staff.disable.club",
        "client.staff.revoke.lower"
      ],
      "removed": [],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.club.calendar.write": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.club.profile.read": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.club.profile.write": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.assign.entry_manager": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.assign.finance_manager": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.assign.manager": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.assign.promoter": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.delegate.calendar": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.delegate.profile": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.disable.club": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        },
        "client.staff.revoke.lower": {
          "after": [
            "ROLE:new-cm"
          ],
          "before": []
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {
      "client.club.calendar.read": [
        "ROLE:new-cm"
      ],
      "client.club.calendar.write": [
        "ROLE:new-cm"
      ],
      "client.club.profile.read": [
        "ROLE:new-cm"
      ],
      "client.club.profile.write": [
        "ROLE:new-cm"
      ],
      "client.staff.assign.entry_manager": [
        "ROLE:new-cm"
      ],
      "client.staff.assign.finance_manager": [
        "ROLE:new-cm"
      ],
      "client.staff.assign.manager": [
        "ROLE:new-cm"
      ],
      "client.staff.assign.promoter": [
        "ROLE:new-cm"
      ],
      "client.staff.delegate.calendar": [
        "ROLE:new-cm"
      ],
      "client.staff.delegate.profile": [
        "ROLE:new-cm"
      ],
      "client.staff.disable.club": [
        "ROLE:new-cm"
      ],
      "client.staff.revoke.lower": [
        "ROLE:new-cm"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "owner-assigns-club-manager-without-execution-config-grants",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## club-manager-full-disable-cannot-remove-opaque-sources

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.read": [
        "COMPAT:read"
      ],
      "client.club.team.manage": [
        "COMPAT:team"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [],
      "source_changes": {}
    }
  },
  "RISK": [
    "FULL_DISABLE_HIERARCHY_DENIED"
  ],
  "TARGET": {
    "101/1001/7": {
      "client.club.read": [
        "COMPAT:read"
      ],
      "client.club.team.manage": [
        "COMPAT:team"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      }
    }
  },
  "external_writes": 0,
  "id": "club-manager-full-disable-cannot-remove-opaque-sources",
  "input_unchanged": true,
  "outcomes": [
    "FULL_DISABLE_HIERARCHY_DENIED"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## owner-full-disable-compatibility-only-subject

```json
{
  "CURRENT": {
    "101/1001/7": {
      "client.club.profile.write": [
        "COMPAT:profile"
      ],
      "client.club.read": [
        "COMPAT:read"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/7": {
      "added": [],
      "removed": [
        "client.club.profile.write",
        "client.club.read"
      ],
      "source_changes": {
        "client.club.profile.write": {
          "after": [],
          "before": [
            "COMPAT:profile"
          ]
        },
        "client.club.read": {
          "after": [],
          "before": [
            "COMPAT:read"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/7": {}
  },
  "authority_revisions": {
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": false,
        "epoch": 2,
        "mode": "TARGET",
        "revoked_through_invitation_id": 1000,
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "owner-full-disable-compatibility-only-subject",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## issuer-revoke-fences-dependent-recipient

```json
{
  "CURRENT": {
    "101/1001/2": {
      "client.club.calendar.read": [
        "ROLE:issuer-cm"
      ],
      "client.club.calendar.write": [
        "ROLE:issuer-cm"
      ],
      "client.club.profile.read": [
        "ROLE:issuer-cm"
      ],
      "client.club.profile.write": [
        "ROLE:issuer-cm"
      ],
      "client.staff.assign.entry_manager": [
        "ROLE:issuer-cm"
      ],
      "client.staff.assign.finance_manager": [
        "ROLE:issuer-cm"
      ],
      "client.staff.assign.manager": [
        "ROLE:issuer-cm"
      ],
      "client.staff.assign.promoter": [
        "ROLE:issuer-cm"
      ],
      "client.staff.delegate.calendar": [
        "ROLE:issuer-cm"
      ],
      "client.staff.delegate.profile": [
        "ROLE:issuer-cm"
      ],
      "client.staff.disable.club": [
        "ROLE:issuer-cm"
      ],
      "client.staff.revoke.lower": [
        "ROLE:issuer-cm"
      ]
    },
    "101/1001/7": {
      "client.club.calendar.read": [
        "DELEGATION:d-calendar"
      ],
      "client.club.calendar.write": [
        "DELEGATION:d-calendar"
      ],
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "DIFFERENCE": {
    "101/1001/2": {
      "added": [],
      "removed": [
        "client.club.calendar.read",
        "client.club.calendar.write",
        "client.club.profile.read",
        "client.club.profile.write",
        "client.staff.assign.entry_manager",
        "client.staff.assign.finance_manager",
        "client.staff.assign.manager",
        "client.staff.assign.promoter",
        "client.staff.delegate.calendar",
        "client.staff.delegate.profile",
        "client.staff.disable.club",
        "client.staff.revoke.lower"
      ],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.club.calendar.write": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.club.profile.read": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.club.profile.write": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.assign.entry_manager": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.assign.finance_manager": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.assign.manager": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.assign.promoter": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.delegate.calendar": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.delegate.profile": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.disable.club": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        },
        "client.staff.revoke.lower": {
          "after": [],
          "before": [
            "ROLE:issuer-cm"
          ]
        }
      }
    },
    "101/1001/7": {
      "added": [],
      "removed": [
        "client.club.calendar.read",
        "client.club.calendar.write"
      ],
      "source_changes": {
        "client.club.calendar.read": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        },
        "client.club.calendar.write": {
          "after": [],
          "before": [
            "DELEGATION:d-calendar"
          ]
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/2": {},
    "101/1001/7": {
      "night.read": [
        "ROLE:a-manager"
      ],
      "tables.read": [
        "ROLE:a-manager"
      ],
      "tables.seat": [
        "ROLE:a-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/2": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    },
    "101/1001/7": {
      "CURRENT": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 1,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "issuer-revoke-fences-dependent-recipient",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```

## initial-target-admission-creates-only-missing-membership

```json
{
  "CURRENT": {
    "101/1001/11": {}
  },
  "DIFFERENCE": {
    "101/1001/11": {
      "added": [
        "night.read",
        "tables.read",
        "tables.seat"
      ],
      "removed": [],
      "source_changes": {
        "night.read": {
          "after": [
            "ROLE:initial-manager"
          ],
          "before": []
        },
        "tables.read": {
          "after": [
            "ROLE:initial-manager"
          ],
          "before": []
        },
        "tables.seat": {
          "after": [
            "ROLE:initial-manager"
          ],
          "before": []
        }
      }
    }
  },
  "RISK": [],
  "TARGET": {
    "101/1001/11": {
      "night.read": [
        "ROLE:initial-manager"
      ],
      "tables.read": [
        "ROLE:initial-manager"
      ],
      "tables.seat": [
        "ROLE:initial-manager"
      ]
    }
  },
  "authority_revisions": {
    "101/1001/11": {
      "CURRENT": {
        "active": false,
        "epoch": 1,
        "mode": "TARGET",
        "version": 1
      },
      "TARGET": {
        "active": true,
        "epoch": 2,
        "mode": "TARGET",
        "version": 2
      }
    }
  },
  "external_writes": 0,
  "id": "initial-target-admission-creates-only-missing-membership",
  "input_unchanged": true,
  "outcomes": [
    "COMMITTED_IN_MODEL_ONLY"
  ],
  "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"
}
```
