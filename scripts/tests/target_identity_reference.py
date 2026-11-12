#!/usr/bin/env python3
"""PROPOSED, offline artificial-value model. No production imports, DB, network or apply mode."""
import argparse
import copy
import hashlib
import hmac
import json
from pathlib import Path
import re
from urllib.parse import parse_qsl, urlencode

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'scripts/tests/fixtures/role-target-identity-contract.json'
CONTRACT = 'docs/product/ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md'
EVIDENCE_SOURCES = (
    CONTRACT, 'docs/product/ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md',
    'docs/product/PRODUCT_SPEC.md', 'docs/product/OPEN_DECISIONS.md',
    'docs/ops/owner-team-contract.md', 'docs/ops/owner-manager-contract.md',
    'core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt',
    'app-bot/src/main/kotlin/com/example/bot/plugins/ClientContextAuth.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentProviders.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAuthority.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycle.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAdmissionWriter.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/assignments/JdbcRoleAssignmentFoundation.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/assignments/PlatformMembershipSourceInvalidator.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubTeamOperations.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt',
    'core-data/src/main/resources/db/migration/h2/V077__client_role_assignment_foundation.sql',
    'core-data/src/main/resources/db/migration/postgresql/V077__client_role_assignment_foundation.sql',
)
ROLES = {'NETWORK_MANAGER', 'CLUB_MANAGER', 'FLOOR_MANAGER', 'ENTRANCE_MANAGER', 'FINANCE_MANAGER', 'PROMOTER'}
LOWER = ROLES - {'NETWORK_MANAGER', 'CLUB_MANAGER'}
SUFFIX = {'NETWORK_MANAGER': 'network_manager', 'CLUB_MANAGER': 'club_manager', 'FLOOR_MANAGER': 'manager',
          'ENTRANCE_MANAGER': 'entry_manager', 'FINANCE_MANAGER': 'finance_manager', 'PROMOTER': 'promoter'}
TRANSPORT = {'actor', 'key', 'age', 'connection', 'wait', 'resolve', 'signature_bad', 'duplicate_keys',
             'envelope_bad', 'challenge_replay', 'audit_failure', 'body_user', 'claimant', 'comparison_ok'}


class Denied(Exception):
    pass


def require(condition, code):
    if not condition:
        raise Denied(code)


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def digest(value):
    return hashlib.sha256(encoded(value).encode()).hexdigest()


def scope(client, club, user):
    return f'{client}/{club}/{user}'


def member_key(client, user):
    return f'{client}/{user}'


def policy_hash(policy):
    return digest({k: policy[k] for k in ('role', 'version', 'permissions')})


def live_source(state, row):
    member = state['memberships'].get(member_key(row['client'], row['user']), {})
    fence = state['admissions'].get(scope(row['client'], row['club'], row['user']), {})
    policy = state['policies'].get(row['policy'], {})
    return (state['clients'].get(row['client'], {}).get('active') is True
            and state['clubs'].get(row['club']) == row['client']
            and row['status'] == 'ACTIVE' and member.get('active') is True and fence.get('status') == 'ACTIVE'
            and fence.get('member_version') == member.get('version') and fence.get('epoch') == row['epoch']
            and policy.get('active') is True and row['policy_hash'] == policy_hash(policy))


def effective(state, key, resource=None):
    client, club, user = key.split('/')
    result = {}
    for id, row in state['sources'].items():
        if (row['client'], row['club'], str(row['user'])) == (client, club, user) and live_source(state, row):
            selector = row.get('selector', {'kind': 'CLUB'})
            if selector['kind'] == 'SHIFT_SET' and (resource or {}).get('shift') not in selector['ids']:
                continue
            for permission in state['policies'][row['policy']]['permissions']:
                result.setdefault(permission, []).append('ROLE:' + id)
    for id, row in state['delegations'].items():
        if scope(row['client'], row['club'], row['user']) == key and row['status'] == 'ACTIVE':
            if all(live_source(state, state['sources'][p]) for p in row['parents']):
                for permission in row['permissions']:
                    result.setdefault(permission, []).append('DELEGATION:' + id)
    return {p: sorted(ids) for p, ids in sorted(result.items())}


def authority(state, actor, client, club, role, operation='assign'):
    require(state['clients'].get(client, {}).get('active') is True and state['clubs'].get(club) == client, 'SCOPE_DENIED')
    member = state['memberships'].get(member_key(client, actor), {})
    require(member.get('active') is True and actor != 99, 'ISSUER_DENIED')
    owner = state['owners'].get(member_key(client, actor))
    if owner and owner['active'] and owner['verified'] and club in owner['clubs'] and role in {'NETWORK_MANAGER', 'CLUB_MANAGER'}:
        return 'OWNER:' + owner['id'] + ':' + str(owner['version'])
    for id, source in sorted(state['sources'].items()):
        permitted = ((source['role'] == 'NETWORK_MANAGER' and role == 'CLUB_MANAGER')
                     or (source['role'] == 'CLUB_MANAGER' and role in LOWER))
        if (source['user'] == actor and source['client'] == client and source['club'] == club
                and permitted and live_source(state, source)
                and f'client.staff.{operation}.{SUFFIX[role]}' in state['policies'][source['policy']]['permissions']):
            return f'SOURCE:{id}:{source["version"]}:{source["epoch"]}'
    raise Denied('ISSUER_DENIED')


def strict_pairs(items):
    out = {}
    for key, value in items:
        require(key not in out, 'DUPLICATE_KEY')
        out[key] = value
    return out


def sign_identity(user, connection, auth_date):
    # Public artificial keys, used only for offline HMAC acceptance. Never Telegram credentials.
    fields = {'user': encoded({'id': user}), 'auth_date': str(auth_date), 'signature': 'synthetic-signature'}
    data = '\n'.join(f'{k}={v}' for k, v in sorted(fields.items()))
    key = hmac.new(b'WebAppData', ('offline-fixture:' + connection).encode(), hashlib.sha256).digest()
    fields['hash'] = hmac.new(key, data.encode(), hashlib.sha256).hexdigest()
    return urlencode(fields)


def verify_identity(raw, connection, now):
    require(isinstance(raw, str) and len(raw) <= 8192, 'IDENTITY_DENIED')
    try:
        require(re.search(r'%(?![0-9A-Fa-f]{2})', raw) is None, 'IDENTITY_DENIED')
        params = strict_pairs(parse_qsl(raw, keep_blank_values=True, strict_parsing=True, errors='strict'))
        require(re.fullmatch('[0-9a-f]{64}', params.get('hash', '')) is not None, 'IDENTITY_DENIED')
        data = '\n'.join(f'{k}={v}' for k, v in sorted(params.items()) if k != 'hash')
        key = hmac.new(b'WebAppData', ('offline-fixture:' + connection).encode(), hashlib.sha256).digest()
        expected = hmac.new(key, data.encode(), hashlib.sha256).hexdigest()
        require(hmac.compare_digest(expected, params['hash']), 'IDENTITY_DENIED')
        require(re.fullmatch('[0-9]+', params.get('auth_date', '')) is not None, 'IDENTITY_DENIED')
        date = int(params['auth_date'])
        require(0 <= now - date < 300, 'IDENTITY_EXPIRED')
        user = json.loads(params['user'], object_pairs_hook=strict_pairs)['id']
        require(type(user) is int and 0 < user < 2**63, 'IDENTITY_DENIED')
        return user, date + 300
    except (KeyError, ValueError, UnicodeError, TypeError):
        raise Denied('IDENTITY_DENIED') from None


def intent(op):
    return {k: v for k, v in op.items() if k not in TRANSPORT}


def packet(state, op, cache):
    invite = state['invites'].get(op.get('invite'))
    default = invite['recipient_connection'] if invite and op['op'] in {'claim', 'refresh'} else 'canonical'
    connection = op.get('connection', default)
    body = intent(op)
    pinned_invite = state['invites'].get(op.get('previous')) if op['op'] == 'reissue' else invite
    # Cache is transport-only test harness memory, never persisted state or report output.
    cache_key = (op['actor'], op['key'])
    if cache_key not in cache:
        payload = {'actor': op['actor'], 'connection': connection,
                   'audience_version': state['audiences'].get(connection, {}).get('version', 0),
                   'client': invite['client'] if invite else op.get('client'),
                   'club': invite['club'] if invite else op.get('club'),
                   'invitation_version': pinned_invite['version'] if pinned_invite else 0,
                   'intent_hash': digest(body),
                   'nonce_hash': digest(op['key']), 'expires': state['now'] + 120}
        cache[cache_key] = {'payload': payload, 'mac': hmac.new(b'offline-intent-envelope', encoded(payload).encode(), hashlib.sha256).hexdigest()}
    selected = (op['actor'], op.get('challenge_replay', op['key']))
    require(selected in cache, 'ENVELOPE_DENIED')
    envelope = copy.deepcopy(cache[selected])
    if op.get('envelope_bad'):
        envelope['payload']['intent_hash'] = 'altered'
    raw = sign_identity(op['actor'], connection, state['now'] - op.get('age', 0))
    if op.get('signature_bad'):
        raw = raw.replace('synthetic-signature', 'altered-signature')
    if op.get('duplicate_keys'):
        raw += '&user=' + urlencode({'v': encoded({'id': op['actor']})})[2:]
    return {'raw': raw, 'connection': connection, 'envelope': envelope}


def floor_and_epoch(state, row, user):
    member = state['memberships'].get(member_key(row['client'], user))
    fence = state['admissions'].get(scope(row['client'], row['club'], user))
    require(state['frontier_known'], 'FRONTIER_UNKNOWN')
    markers = [state['clients'][row['client']]['seq']]
    markers += [r.get('floor') for r in (member, fence) if r is not None]
    require(all(type(n) is int and n >= 0 for n in markers), 'FRONTIER_UNKNOWN')
    floor = max(member['floor'] if member else 0, fence['floor'] if fence else 0)
    require(row['seq'] > floor, 'INVITATION_FENCED')
    require(member is None or member['active'], 'MEMBERSHIP_INACTIVE')
    return member, fence


def recipient_protected(state, actor, client, user):
    require(state['protection_known'], 'PROTECTION_UNKNOWN')
    owner = state['owners'].get(member_key(client, user))
    require(user != actor and user != 99 and not (owner and owner['active']), 'PROTECTED_SUBJECT')


def view_receipt(state, row, actor, operation):
    if operation in {'claim', 'refresh'}:
        require(row['claimant'] == actor, 'RECIPIENT_MISMATCH')
    else:
        require(row['issuer'] == actor, 'ISSUER_DENIED')
        authority(state, actor, row['client'], row['club'], row['role'])
    return 'REPLAY_CURRENT'


def transact(original, op, cache):
    state = copy.deepcopy(original)
    try:
        p = packet(state, op, cache)
        now = state['now'] + op.get('wait', 0)
        binding = state['audiences'].get(p['connection'])
        require(binding is not None and binding['active'], 'AUDIENCE_DENIED')
        actor, valid_until = verify_identity(p['raw'], p['connection'], now)
        envelope = p['envelope']; payload = envelope['payload']
        mac = hmac.new(b'offline-intent-envelope', encoded(payload).encode(), hashlib.sha256).hexdigest()
        require(hmac.compare_digest(mac, envelope['mac']), 'ENVELOPE_DENIED')
        require(payload['audience_version'] == binding['version'], 'AUDIENCE_DENIED')
        require(payload['actor'] == actor and payload['connection'] == p['connection']
                and payload['intent_hash'] == digest(intent(op))
                and payload['nonce_hash'] == digest(op['key']), 'INTENT_DENIED')
        receipt_key = f'{actor}/{p["connection"]}/{payload["nonce_hash"]}'
        existing = state['receipts'].get(receipt_key)
        if existing:
            require(existing['intent_hash'] == payload['intent_hash'], 'IDEMPOTENCY_CONFLICT')
            row = state['invites'][existing['invite']]
            require(row['club'] in binding['clients'].get(row['client'], []), 'AUDIENCE_DENIED')
            return original, view_receipt(state, row, actor, op['op'])
        if op.get('resolve'):
            return original, 'NOT_COMMITTED_AT_SNAPSHOT'
        require(payload['expires'] > now, 'CHALLENGE_EXPIRED')
        action = op['op']
        if action in {'issue', 'reissue'}:
            if action == 'reissue':
                prior = state['invites'][op['previous']]
                require((prior['client'], prior['club'], prior['role']) == (op['client'], op['club'], op['role']), 'SCOPE_DENIED')
                require(prior['version'] == payload['invitation_version'], 'VERSION_CONFLICT')
                authority(state, actor, prior['client'], prior['club'], prior['role'], 'revoke')
                require(prior['status'] != 'CONSUMED', 'INVITATION_TERMINAL')
                if prior['status'] in {'ISSUED', 'CLAIMED'} and now < prior['expires']:
                    prior.update(status='REVOKED', version=prior['version'] + 1, secret_consumed=True)
            issue(state, op, actor, binding, p['connection'], now)
        else:
            row = state['invites'].get(op['invite'])
            require(row is not None, 'INVITATION_NOT_FOUND')
            require(row['version'] == payload['invitation_version'], 'VERSION_CONFLICT')
            require(row['club'] in binding['clients'].get(row['client'], []), 'AUDIENCE_DENIED')
            if 'expected_version' in op:
                require(row['version'] == op['expected_version'], 'VERSION_CONFLICT')
            if action in {'claim', 'refresh'}:
                claim(state, op, row, actor, p['connection'], binding, now, valid_until)
            elif action == 'confirm':
                confirm(state, op, row, actor, p['connection'], binding, now)
            elif action == 'cancel':
                authority(state, actor, row['client'], row['club'], row['role'], 'revoke')
                require(row['status'] in {'ISSUED', 'CLAIMED'}, 'INVITATION_TERMINAL')
                row.update(status='REVOKED', version=row['version'] + 1, secret_consumed=True)
            else:
                raise Denied('UNKNOWN_OPERATION')
        row = state['invites'][op['invite']]
        state['receipts'][receipt_key] = {'invite': op['invite'], 'intent_hash': payload['intent_hash']}
        state['audits'].append({'operation': action, 'client': row['client'], 'club': row['club'], 'actor': actor})
        require(not op.get('audit_failure'), 'AUDIT_FAILURE_ROLLBACK')
        return state, 'COMMITTED'
    except Denied as failure:
        return original, str(failure)


def issue(state, op, actor, binding, connection, now):
    client, club, role = op['client'], op['club'], op['role']
    require(role in ROLES, 'ROLE_DENIED')
    require(club in binding['clients'].get(client, []), 'AUDIENCE_DENIED')
    ref = authority(state, actor, client, club, role)
    require(op['invite'] not in state['invites'], 'INVITATION_TERMINAL')
    target = op.get('target')
    require(op.get('mode', 'FIRST') == 'FIRST' or target is not None, 'RECIPIENT_REFERENCE_REQUIRED')
    if target is not None:
        recipient_protected(state, actor, client, target)
    policy = state['policies'].get(op['policy'])
    require(policy is not None and policy['active'] and policy['role'] == role, 'POLICY_DENIED')
    selector = copy.deepcopy(op.get('selector', {'kind': 'CLUB'}))
    if role == 'FINANCE_MANAGER':
        valid_shift_set = (selector.get('kind') == 'SHIFT_SET' and bool(selector.get('ids'))
                           and all(type(i) is int and i > 0 for i in selector['ids']))
        require(valid_shift_set or selector.get('kind') == 'ALL_SHIFTS_OF_THIS_CLUB', 'SELECTOR_DENIED')
    else:
        require(selector == {'kind': 'CLUB'}, 'SELECTOR_DENIED')
    recipient_connection = op.get('recipient_connection', 'canonical')
    recipient_binding = state['audiences'].get(recipient_connection)
    require(recipient_binding is not None and recipient_binding['active']
            and club in recipient_binding['clients'].get(client, []), 'AUDIENCE_DENIED')
    state['clients'][client]['seq'] += 1
    state['invites'][op['invite']] = dict(
        client=client, club=club, role=role, policy=op['policy'], policy_hash=policy_hash(policy),
        eligibility_revision=policy['revision'], issuer=actor, issuer_ref=ref, issuer_connection=connection,
        issuer_binding_version=binding['version'], recipient_connection=recipient_connection,
        recipient_binding_version=recipient_binding['version'], target=target, mode=op.get('mode', 'FIRST'),
        seq=state['clients'][client]['seq'], status='ISSUED', version=1, expires=now + 7 * 86400,
        # Artificial fixed nonce digest; production CSPRNG generation/storage is not implemented here.
        secret_hash=digest(['target-invitation-v1', 'offline-only-invitation:' + op['invite']]), secret_consumed=False,
        source=op.get('source', 'target-' + op['invite']), claimant=None, selector=selector)


def claim(state, op, row, actor, connection, binding, now, valid_until):
    require(row['status'] in {'ISSUED', 'CLAIMED'} and now < row['expires'], 'INVITATION_TERMINAL_OR_EXPIRED')
    require(connection == row['recipient_connection'] and binding['version'] == row['recipient_binding_version'], 'AUDIENCE_DENIED')
    recipient_protected(state, row['issuer'], row['client'], actor)
    require(row['target'] is None or row['target'] == actor, 'RECIPIENT_MISMATCH')
    require(authority(state, row['issuer'], row['client'], row['club'], row['role']) == row['issuer_ref'], 'ISSUER_SOURCE_CHANGED')
    member, fence = floor_and_epoch(state, row, actor)
    if op['op'] == 'claim':
        require(not row['secret_consumed'] and hmac.compare_digest(digest(['target-invitation-v1', op.get('secret', 'offline-only-invitation:' + op['invite'])]), row['secret_hash']), 'SECRET_CONSUMED_OR_INVALID')
        row['claimant'] = actor
        row['secret_consumed'] = True
    else:
        require(row['status'] == 'CLAIMED' and row['claimant'] == actor, 'RECIPIENT_MISMATCH')
        require(row['claim_epoch'] == (fence['epoch'] if fence else 0), 'EPOCH_CHANGED')
    row.update(status='CLAIMED', version=row['version'] + 1, claim_valid_until=valid_until,
               claim_member_version=member['version'] if member else 0,
               claim_epoch=fence['epoch'] if fence else 0, claim_revision=fence['version'] if fence else 0,
               claim_audience_version=binding['version'], code_revision=row['version'] + 1)


def confirm(state, op, row, actor, connection, binding, now):
    require(actor == row['issuer'], 'ISSUER_DENIED')
    require(connection == row['issuer_connection'] and binding['version'] == row['issuer_binding_version'], 'AUDIENCE_DENIED')
    require(authority(state, actor, row['client'], row['club'], row['role']) == row['issuer_ref'], 'ISSUER_SOURCE_CHANGED')
    require(row['status'] == 'CLAIMED' and now < row['expires'], 'INVITATION_TERMINAL_OR_EXPIRED')
    user = row['claimant']
    require(op.get('claimant') == user and op.get('comparison_ok') is True and op.get('confirmed') is True, 'EXPLICIT_CONFIRMATION_REQUIRED')
    recipient_protected(state, actor, row['client'], user)
    rb = state['audiences'][row['recipient_connection']]
    require(rb['active'] and rb['version'] == row['claim_audience_version'], 'AUDIENCE_DENIED')
    require(now < row['claim_valid_until'], 'RECIPIENT_PROOF_EXPIRED')
    member, fence = floor_and_epoch(state, row, user)
    require(row['claim_member_version'] == (member['version'] if member else 0), 'MEMBERSHIP_VERSION_CHANGED')
    require(row['claim_epoch'] == (fence['epoch'] if fence else 0)
            and row['claim_revision'] == (fence['version'] if fence else 0), 'EPOCH_CHANGED')
    policy = state['policies'][row['policy']]
    require(policy['active'] and policy_hash(policy) == row['policy_hash']
            and policy['revision'] == row['eligibility_revision'], 'POLICY_DENIED')
    key = scope(row['client'], row['club'], user)
    if row['mode'] == 'FIRST':
        require(fence is None and (member is None or member['floor'] == 0), 'READMISSION_REQUIRED')
    elif row['mode'] == 'ADDITIONAL':
        require(fence is not None and fence['status'] == 'ACTIVE', 'ADMISSION_REQUIRED')
    elif row['mode'] == 'READMIT':
        require(fence is None or fence['status'] == 'DISABLED', 'READMISSION_REQUIRED')
    else:
        raise Denied('MODE_DENIED')
    revoked = set(state['revoked_permissions'].get(key, []))
    require(set(op.get('regrant_ack', [])) == set(policy['permissions']) & revoked, 'REGRANT_ACK_REQUIRED')
    require(row['source'] not in state['sources'] and row['source'] not in state['delegations'], 'SOURCE_ID_TERMINAL')
    if member is None:
        member = {'active': True, 'version': 1, 'floor': 0}
        state['memberships'][member_key(row['client'], user)] = member
    if row['mode'] != 'ADDITIONAL':
        epoch = fence['epoch'] + 1 if fence else 1
        floor = fence['floor'] if fence else 0
        revision = fence['version'] + 1 if fence else 1
        fence = {'status': 'ACTIVE', 'epoch': epoch, 'version': revision, 'floor': floor, 'member_version': member['version']}
        state['admissions'][key] = fence
    state['sources'][row['source']] = dict(client=row['client'], club=row['club'], user=user, role=row['role'],
        policy=row['policy'], policy_hash=row['policy_hash'], selector=copy.deepcopy(row['selector']), epoch=fence['epoch'], version=1, status='ACTIVE')
    fence['version'] += 1
    row.update(status='CONSUMED', version=row['version'] + 1)


def revoke_sources(state, ids):
    for id in ids:
        source = state['sources'][id]
        if source['status'] == 'ACTIVE':
            source.update(status='REVOKED', version=source['version'] + 1)
            key = scope(source['client'], source['club'], source['user'])
            state['revoked_permissions'].setdefault(key, []).extend(state['policies'][source['policy']]['permissions'])
    direct_scopes = {scope(state['sources'][id]['client'], state['sources'][id]['club'], state['sources'][id]['user']) for id in ids}
    dependent_scopes = set()
    for row in state['delegations'].values():
        if row['status'] == 'ACTIVE' and set(row['parents']) & set(ids):
            row['status'] = 'REVOKED'
            key = scope(row['client'], row['club'], row['user'])
            state['revoked_permissions'].setdefault(key, []).extend(row['permissions'])
            dependent_scopes.add(key)
    for key in dependent_scopes - direct_scopes:
        fence = state['admissions'][key]
        client = key.split('/')[0]
        fence.update(version=fence['version'] + 1, floor=max(fence['floor'], state['clients'][client]['seq']))


def event(state, op):
    """External already-authorized commits in the value model, not new production writers."""
    state = copy.deepcopy(state); action = op['op']
    if action == 'tick':
        state['now'] += op['seconds']
    elif action == 'revoke_owner':
        state['owners'][member_key(op['client'], op['user'])]['active'] = False
    elif action in {'source_revoke', 'full_disable', 'platform_revoke'}:
        client, user = op['client'], op['user']
        clubs = [op['club']] if action != 'platform_revoke' else [c for c, owner in state['clubs'].items() if owner == client]
        if action == 'platform_revoke':
            member = state['memberships'][member_key(client, user)]
            member.update(active=False, version=member['version'] + 1, floor=max(member['floor'], state['clients'][client]['seq']))
        for club in clubs:
            key = scope(client, club, user); fence = state['admissions'].get(key)
            ids = [id for id, r in state['sources'].items() if scope(r['client'], r['club'], r['user']) == key
                   and (action != 'source_revoke' or id == op['source'])]
            revoke_sources(state, ids)
            if action == 'full_disable':
                require(fence is not None, 'ADMISSION_REQUIRED')
            if fence:
                fence.update(version=fence['version'] + 1, floor=max(fence['floor'], state['clients'][client]['seq']))
                if action != 'source_revoke':
                    fence.update(status='DISABLED', epoch=fence['epoch'] + 1)
    elif action == 'platform_restore':
        member = state['memberships'][member_key(op['client'], op['user'])]
        member.update(active=True, version=member['version'] + 1)
    elif action == 'policy_retire':
        state['policies'][op['policy']].update(active=False, revision=state['policies'][op['policy']]['revision'] + 1)
    elif action == 'policy_v2':
        prior = state['policies'][op['policy']]
        state['policies'][op['policy'] + '-v2'] = dict(prior, version=2, permissions=prior['permissions'] + ['future.proposed.permission'])
    elif action == 'audience_revoke':
        state['audiences'][op['connection']].update(active=False, version=state['audiences'][op['connection']]['version'] + 1)
    elif action == 'delegate':
        issuer, recipient = state['sources'][op['issuer']], state['sources'][op['recipient']]
        require(issuer['role'] == 'CLUB_MANAGER' and recipient['role'] == 'FLOOR_MANAGER'
                and live_source(state, issuer) and live_source(state, recipient)
                and issuer['client'] == recipient['client'] and issuer['club'] == recipient['club'], 'DELEGATION_DENIED')
        require({'client.club.calendar.read', 'client.club.calendar.write', 'client.staff.delegate.calendar'}
                <= set(state['policies'][issuer['policy']]['permissions']), 'DELEGATION_DENIED')
        state['delegations'][op['id']] = dict(client=recipient['client'], club=recipient['club'], user=recipient['user'],
            status='ACTIVE', parents=[op['issuer'], op['recipient']], permissions=['client.club.calendar.read', 'client.club.calendar.write'])
    else:
        raise ValueError('unknown synthetic event')
    return state, 'EVENT'


def run_case(initial, case):
    state = copy.deepcopy(initial)
    for key, value in case.get('initial_overrides', {}).items():
        require(key in {'frontier_known', 'protection_known'}, 'SYNTHETIC_OVERRIDE_DENIED')
        state[key] = value
    cache = {}; outcomes = []; unchanged_on_denial = True
    before = {k: effective(state, k, case.get('resources', {}).get(k)) for k in case['observe']}
    for op in case['operations']:
        previous = copy.deepcopy(state)
        if op['op'] in {'issue', 'reissue', 'claim', 'refresh', 'confirm', 'cancel'}:
            state, outcome = transact(state, op, cache)
            if outcome not in {'COMMITTED', 'REPLAY_CURRENT', 'NOT_COMMITTED_AT_SNAPSHOT'}:
                unchanged_on_denial &= state == previous
        else:
            state, outcome = event(state, op)
        outcomes.append(outcome)
    after = {k: effective(state, k, case.get('resources', {}).get(k)) for k in case['observe']}
    difference = {k: {'added': sorted(set(after[k]) - set(before[k])), 'removed': sorted(set(before[k]) - set(after[k]))} for k in before}
    return {'id': case['id'], 'CURRENT': before, 'TARGET': after, 'DIFFERENCE': difference, 'RISK': case['risk'],
            'outcomes': outcomes, 'denials_atomic': unchanged_on_denial, 'external_writes': 0, 'state': state}


def load_fixture():
    value = json.loads(FIXTURE.read_text())
    require(value.get('synthetic_only') is True, 'SYNTHETIC_ONLY')
    require(set(value['source_sha256']) == set(EVIDENCE_SOURCES), 'SOURCE_SET_DRIFT')
    for path, pin in value['source_sha256'].items():
        require(pin == {'algorithm': 'SHA-256', 'digest': hashlib.sha256((ROOT / path).read_bytes()).hexdigest()}, 'SOURCE_DRIFT')
    require(len({c['id'] for c in value['cases']}) == len(value['cases']), 'DUPLICATE_CASE')
    return value


def render(fixture):
    lines = ['# TARGET identity — synthetic reference report', '', '**PROPOSED; artificial values only; zero DB/API/network writes.**',
             'Actual HMAC operations use public synthetic keys. No Kotlin/runtime/SQL-lock proof is claimed.',
             '[Technical contract](../product/ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md).', '']
    for case in fixture['cases']:
        r = run_case(fixture['state'], case)
        controls = {'invitations': {id: {k: row.get(k) for k in ('status', 'claimant', 'seq', 'source')} for id, row in r['state']['invites'].items()},
                    'admission_changes': {k: v for k, v in r['state']['admissions'].items() if v != fixture['state']['admissions'].get(k)},
                    'membership_changes': {k: v for k, v in r['state']['memberships'].items() if v != fixture['state']['memberships'].get(k)},
                    'source_changes': {id: row['status'] for id, row in r['state']['sources'].items() if row != fixture['state']['sources'].get(id)}}
        lines += ['## ' + case['id'], '', '```json', json.dumps({k: r[k] for k in ('CURRENT', 'TARGET', 'DIFFERENCE', 'RISK', 'outcomes', 'denials_atomic', 'external_writes')}, ensure_ascii=False, indent=2),
                  '```', '', '<details><summary>Synthetic control state</summary>', '', '```json', json.dumps(controls, indent=2), '```', '</details>', '']
    return '\n'.join(lines)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--format', choices=('markdown', 'json'), default='markdown')
    args = parser.parse_args(); fixture = load_fixture()
    if args.format == 'markdown':
        print(render(fixture), end='')
    else:
        print(json.dumps([{k: v for k, v in run_case(fixture['state'], c).items() if k != 'state'} for c in fixture['cases']], indent=2))
