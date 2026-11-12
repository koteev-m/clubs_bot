"""PROPOSED offline state machine. Synthetic metadata only: no DB, network, credentials or apply mode."""
import copy
import hashlib
import json
from pathlib import Path

from scripts.tests import target_identity_reference as identity

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'scripts/tests/fixtures/role-target-trusted-binding.json'
CONTRACT = 'docs/product/ROLE_TARGET_TRUSTED_BINDING_CONTRACT_PROPOSED.md'
MAX_COUNTER = 2**63 - 1
SOURCES = (
    CONTRACT, 'docs/product/PRODUCT_SPEC.md', 'docs/product/OPEN_DECISIONS.md',
    'docs/product/MANAGED_BOTS_DIRECTION.md', 'docs/product/ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md',
    'docs/product/ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md',
    'core-domain/src/main/kotlin/com/example/bot/config/AppConfig.kt',
    'app-bot/src/main/kotlin/com/example/bot/plugins/ClientContextAuth.kt',
    'app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetIdentityVerifier.kt',
    'app-bot/src/main/kotlin/com/example/bot/plugins/target/CanonicalTargetAudienceProvider.kt',
    'core-data/src/main/kotlin/com/example/bot/data/client/JdbcCanonicalClientAudienceRepository.kt',
    'core-data/src/main/resources/db/migration/postgresql/V061__client_context.sql',
    'core-data/src/main/resources/db/migration/postgresql/V066__client_archive.sql',
)


class Denied(Exception):
    pass


class Cancelled(Exception):
    pass


def require(ok, code):
    if not ok:
        raise Denied(code)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def available(state):
    if state['fault'] == 'cancel':
        raise Cancelled('synthetic cancellation')
    require(state['fault'] is None, 'SOURCE_ERROR')
    require(state['source_present'], 'SOURCE_MISSING')
    require(state['writer_guard'], 'FENCE_UNKNOWN')


def credential(state, root_id, root, ref):
    attested = state['credential_attestations'].get(ref, {})
    require(attested.get('root') == root_id and attested.get('bot') == root['bot'], 'CREDENTIAL_DENIED')
    material = attested.get('material')
    require(bool(material) and material not in state['retired_materials'] and
            ref not in state['retired_credentials'], 'CREDENTIAL_DENIED')
    return material


def active(state, root_id, client, club):
    available(state)
    root = state['connections'].get(root_id)
    require(root is not None, 'CONNECTION_UNKNOWN')
    binding = state['bindings'].get(root['current_binding'], {})
    require(binding.get('root') == root_id, 'BINDING_UNKNOWN')
    require(binding['status'] == 'ACTIVE', 'BINDING_INACTIVE')
    require(type(root['generation']) is int and type(root['version']) is int and
            0 < root['generation'] <= MAX_COUNTER and 0 < root['version'] <= MAX_COUNTER, 'FENCE_UNKNOWN')
    tenant = state['clients'].get(client, {})
    require(tenant.get('active') is True and not tenant.get('archived'), 'CLIENT_DENIED')
    require(type(tenant.get('mapping_epoch')) is int and 0 < tenant['mapping_epoch'] <= MAX_COUNTER and
            type(tenant.get('lifecycle')) is int and tenant['lifecycle'] > 0, 'FENCE_UNKNOWN')
    require(state['mappings'].get(club, {}).get('client') == client, 'MAPPING_DENIED')
    if root['kind'] == 'BRANDED':
        require(root['client'] == client and club in binding['clubs'], 'AUDIENCE_DENIED')
        require(bool(binding['clubs']) and all(state['mappings'].get(c, {}).get('client') == client
                                             for c in binding['clubs']), 'AUDIENCE_DENIED')
    else:
        require(root['kind'] == 'PLATFORM' and root['client'] is None and binding['clubs'] is None, 'AUDIENCE_DENIED')
    ref = binding['credential_ref']
    credential(state, root_id, root, ref)
    return root, binding, tenant


def activation_scope(state, root, binding):
    if root['kind'] == 'BRANDED':
        tenant = state['clients'].get(root['client'], {})
        require(tenant.get('active') is True and not tenant.get('archived'), 'CLIENT_DENIED')
        require(type(tenant.get('mapping_epoch')) is int and 0 < tenant['mapping_epoch'] <= MAX_COUNTER and
                type(tenant.get('lifecycle')) is int and tenant['lifecycle'] > 0, 'FENCE_UNKNOWN')
        require(bool(binding['clubs']) and all(state['mappings'].get(c, {}).get('client') == root['client']
                                             for c in binding['clubs']), 'AUDIENCE_DENIED')
    else:
        require(root['kind'] == 'PLATFORM' and root['client'] is None and binding['clubs'] is None, 'AUDIENCE_DENIED')


def pin(root_id, root, binding, tenant, client, club):
    return {'root': root_id, 'binding': root['current_binding'], 'bot': root['bot'],
            'generation': root['generation'], 'version': root['version'], 'credential_ref': binding['credential_ref'],
            'client': client, 'club': club, 'mapping_epoch': tenant['mapping_epoch'], 'lifecycle': tenant['lifecycle']}


def prove(state, op, cache):
    root, binding, tenant = active(state, op['root'], op['client'], op['club'])
    if root['kind'] == 'PLATFORM':
        intent = state['audience_intents'].get(op.get('audience_intent'), {})
        require(intent.get('approved') is True and (intent.get('client'), intent.get('club')) ==
                (op['client'], op['club']), 'PLATFORM_INTENT_REQUIRED')
    ref = binding['credential_ref']
    raw = identity.sign_identity(op['user'], op.get('signed_as', ref), state['now'] - op.get('age', 0))
    try:
        user, expiry = identity.verify_identity(raw, ref, state['now'])
    except identity.Denied:
        raise Denied('IDENTITY_DENIED') from None
    cache[op['as']] = {'pins': pin(op['root'], root, binding, tenant, op['client'], op['club']),
                       'raw': raw, 'user': user, 'expires': expiry}
    return 'IDENTITY_ONLY'


def authorize_target(state, op, cache):
    proof = cache.get(op['proof'])
    require(proof is not None, 'PROOF_MISSING')
    intent = state['intents'].get(op['intent'])
    require(intent is not None, 'INTENT_UNKNOWN')
    require(proof['user'] == intent['actor'], 'ACTOR_DENIED')
    p = proof['pins']
    require((p['client'], p['club']) == (intent['client'], intent['club']), 'INTENT_SCOPE_DENIED')
    root, binding, tenant = active(state, p['root'], intent['client'], intent['club'])
    require(p == pin(p['root'], root, binding, tenant, intent['client'], intent['club']), 'STALE_PROOF')
    try:
        user, _ = identity.verify_identity(proof['raw'], binding['credential_ref'], state['now'])
    except identity.Denied:
        raise Denied('IDENTITY_DENIED') from None
    require(user == intent['actor'] and state['now'] < proof['expires'], 'IDENTITY_DENIED')
    authority = state['authorities'].get(f"{intent['client']}/{intent['club']}/{intent['actor']}", {})
    require(authority.get('active') is True and authority.get('ref') == intent['issuer_ref'], 'AUTHORITY_DENIED')
    member = state['issuer_memberships'].get(f"{intent['client']}/{intent['actor']}", {})
    require(member.get('active') is True and member.get('version') == intent['issuer_member_version'], 'MEMBERSHIP_DENIED')
    require(state['admission_epochs'][intent['client']] == intent['admission_epoch'], 'ADMISSION_DENIED')
    require(state['policy_version'] == intent['policy_version'], 'POLICY_DENIED')
    return p


def target(state, op, cache):
    current = authorize_target(state, op, cache)
    key = f"target:{current['root']}:{state['intents'][op['intent']]['actor']}:" + op['key']
    request = digest({'intent': op['intent'], 'operation': op.get('operation', 'confirm'), 'pins': current})
    old = state['receipts'].get(key)
    if old:
        require(old['request'] == request, 'IDEMPOTENCY_CONFLICT')
        require(old['pins'] == current, 'STALE_RECEIPT')
        return 'REPLAY_CURRENT'
    state['mutations'].append({'intent': op['intent'], 'operation': op.get('operation', 'confirm'), 'pins': current})
    state['audit'].append('TARGET:' + op['intent'])
    require(not op.get('audit_failure'), 'AUDIT_FAILURE')
    state['receipts'][key] = {'request': request, 'pins': current}
    return 'COMMITTED'


def maintenance(state, op):
    available(state)
    require(state['maintenance_current'] and op.get('actor', 99) == 99, 'MAINTENANCE_DENIED')
    try:
        identity.verify_identity(identity.sign_identity(99, 'synthetic-maintenance', state['now'] - op.get('age', 0)),
                                 'synthetic-maintenance', state['now'])
    except identity.Denied:
        raise Denied('MAINTENANCE_DENIED') from None
    root = state['connections'][op['root']]
    binding = state['bindings'][root['current_binding']]
    require(all(type(value) is int and 0 < value <= MAX_COUNTER for value in
                (root['version'], root['generation'], op['version'], op['generation'])), 'FENCE_UNKNOWN')
    key = 'maintenance:' + op['root'] + ':99:' + op['key']
    request = digest({k: v for k, v in op.items() if k not in {'key', 'audit_failure', 'age'}})
    old = state['receipts'].get(key)
    if old:
        require(old['request'] == request, 'IDEMPOTENCY_CONFLICT')
        require(old['generation'] == root['generation'] and old['version'] == root['version']
                and old['binding'] == root['current_binding'], 'STALE_RECEIPT')
        return 'REPLAY_CURRENT'
    require((op['version'], op['generation']) == (root['version'], root['generation']), 'CAS_CONFLICT')
    action = op['action']
    require(binding['status'] != 'REVOKED' or action == 'replace', 'TERMINAL')
    if action in {'disable', 'rotate_start', 'revoke'}:
        require(binding['status'] != 'DISABLED' or action == 'revoke', 'INVALID_TRANSITION')
        binding['status'] = 'REVOKED' if action == 'revoke' else 'DISABLED'
        if action in {'rotate_start', 'revoke'}:
            if binding['credential_ref'] not in state['retired_credentials']:
                state['retired_credentials'].append(binding['credential_ref'])
            material = state['credential_attestations'][binding['credential_ref']]['material']
            if material not in state['retired_materials']:
                state['retired_materials'].append(material)
    elif action in {'activate', 'rotate_finish', 'replace'}:
        expected = {'PENDING', 'DISABLED'} if action == 'activate' else ({'DISABLED'} if action == 'rotate_finish' else {'REVOKED'})
        require(binding['status'] in expected, 'INVALID_TRANSITION')
        ref = op.get('credential_ref', binding['credential_ref'])
        credential(state, op['root'], root, ref)
        if action in {'rotate_finish', 'replace'}:
            require(ref != binding['credential_ref'], 'CREDENTIAL_DENIED')
        if action == 'replace':
            fresh_id = op['new_binding']
            require(fresh_id not in state['bindings'], 'ID_REUSE')
            state['bindings'][fresh_id] = {'root': op['root'], 'status': 'PENDING', 'credential_ref': ref,
                                           'clubs': copy.deepcopy(binding['clubs'])}
            root['current_binding'] = fresh_id
        else:
            activation_scope(state, root, binding)
            binding['credential_ref'] = ref
            binding['status'] = 'ACTIVE'
    elif action == 'scope':
        require(root['kind'] == 'BRANDED' and binding['status'] in {'ACTIVE', 'DISABLED'}, 'INVALID_TRANSITION')
        clubs = op['clubs']
        require(bool(clubs) and len(set(clubs)) == len(clubs) and
                all(state['mappings'].get(c, {}).get('client') == root['client'] for c in clubs), 'AUDIENCE_DENIED')
        binding['clubs'] = sorted(clubs)
    else:
        raise Denied('IMMUTABLE_IDENTITY')
    require(root['version'] < MAX_COUNTER and root['generation'] < MAX_COUNTER, 'FENCE_OVERFLOW')
    root['version'] += 1
    root['generation'] += 1
    state['audit'].append('BINDING:' + action)
    require(not op.get('audit_failure'), 'AUDIT_FAILURE')
    state['receipts'][key] = {'request': request, 'binding': root['current_binding'],
                              'generation': root['generation'], 'version': root['version']}
    return 'COMMITTED'


def event(state, op):
    action = op['action']
    if action in {'mapping_aba', 'new_club'}:
        require(state['writer_guard'], 'FENCE_UNKNOWN')
        client = op['client']
        bumps = 2 if action == 'mapping_aba' else 1
        require(state['clients'][client]['mapping_epoch'] <= MAX_COUNTER - bumps, 'FENCE_OVERFLOW')
        if action == 'mapping_aba':
            old = state['mappings'].pop(op['club'])
            require(old['client'] == client, 'MAPPING_DENIED')
            state['clients'][client]['mapping_epoch'] += 1
            state['mappings'][op['club']] = old  # deliberately identical created_at and IDs
        else:
            require(op['club'] not in state['mappings'], 'MAPPING_DENIED')
            state['mappings'][op['club']] = {'client': client, 'created_at': 1, 'published': False}
        state['clients'][client]['mapping_epoch'] += 1
    elif action == 'source_missing':
        state['source_present'] = False
    elif action in {'db_failure', 'config_failure', 'cancel'}:
        state['fault'] = action
    elif action == 'guard_missing':
        state['writer_guard'] = False
    elif action == 'revoke_authority':
        state['authorities'][op['authority']]['active'] = False
    elif action == 'revoke_membership':
        state['issuer_memberships'][op['member']]['active'] = False
        state['issuer_memberships'][op['member']]['version'] += 1
    elif action == 'policy_change':
        state['policy_version'] += 1
    elif action == 'admission_change':
        state['admission_epochs'][op['client']] += 1
    elif action == 'tick':
        state['now'] += op['seconds']
    else:
        raise Denied('IMMUTABLE_IDENTITY')
    return 'EVENT'


def transact(state, op, cache):
    draft, local = copy.deepcopy(state), copy.deepcopy(cache)
    try:
        if op['op'] == 'proof':
            outcome = prove(draft, op, local)
        elif op['op'] == 'target':
            outcome = target(draft, op, local)
        elif op['op'] == 'maintenance':
            outcome = maintenance(draft, op)
        else:
            outcome = event(draft, op)
        cache.clear()
        cache.update(local)
        return draft, outcome
    except Denied as failure:
        return state, str(failure)
    # Cancellation deliberately propagates; no draft state or transient proof is published.


def run_case(initial, case):
    state, cache = copy.deepcopy(initial), {}
    outcomes = []
    denied_atomic = True
    for op in case['operations']:
        before = copy.deepcopy(state)
        try:
            state, outcome = transact(state, op, cache)
        except Cancelled:
            outcome = 'CANCELLED'
        if outcome not in {'EVENT', 'IDENTITY_ONLY', 'COMMITTED', 'REPLAY_CURRENT'}:
            denied_atomic = denied_atomic and state == before
        outcomes.append(outcome)
    return {'id': case['id'], 'outcomes': outcomes, 'mutations': len(state['mutations']),
            'denied_atomic': denied_atomic, 'external_writes': 0, 'state': state}


def load_fixture(path=FIXTURE):
    fixture = json.loads(path.read_text())
    require(fixture.get('synthetic_only') is True, 'FIXTURE_NOT_SYNTHETIC')
    require(fixture.get('status') == 'PROPOSED', 'FIXTURE_STATUS')
    require(set(fixture['source_sha256']) == set(SOURCES), 'EVIDENCE_SET')
    for path, checksum in fixture['source_sha256'].items():
        require(checksum.get('algorithm') == 'SHA-256' and checksum.get('digest') == hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), 'SOURCE_DRIFT')
    return fixture


if __name__ == '__main__':
    fixture = load_fixture()
    report = []
    for case in fixture['cases']:
        result = run_case(fixture['state'], case)
        report.append({k: result[k] for k in ('id', 'outcomes', 'mutations', 'denied_atomic', 'external_writes')})
    print(json.dumps({'status': 'PROPOSED_SYNTHETIC_ONLY', 'cases': report}, indent=2))
