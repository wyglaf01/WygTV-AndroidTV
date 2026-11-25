"""Check the effective ATS policy in a source or built iOS Info.plist."""
import plistlib
import sys
with open(sys.argv[1], 'rb') as stream:
    info = plistlib.load(stream)
policy = info.get('NSAppTransportSecurity', {})
if policy.get('NSAllowsArbitraryLoads') is not True:
    raise SystemExit('HTTP provider support is missing')
# Presence alone overrides NSAllowsArbitraryLoads, even with a false value.
overrides = {'NSAllowsArbitraryLoadsForMedia', 'NSAllowsArbitraryLoadsInWebContent', 'NSAllowsLocalNetworking'}
if overrides.intersection(policy):
    raise SystemExit('Granular ATS keys override HTTP provider support')
if policy.get('NSExceptionDomains'):
    raise SystemExit('Per-domain exceptions require separate policy verification')
print('Verified effective HTTP provider support in ' + sys.argv[1])
