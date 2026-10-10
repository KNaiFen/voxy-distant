"""Actual screen/control acceptance, invoked by lod-live-benchmark --settings-check."""
import json
import time
import tomllib


def check_settings(client, server, output, server_config, client_command, command, receive=False):
    events = []
    after_ms = 0

    def state():
        rows = (output / 'states.jsonl').read_text(encoding='utf-8').splitlines()
        return json.loads(rows[-1])

    def wait(expected=None):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            current = state()
            if current.get('epoch_ms', 0) > after_ms and current.get('screen') == 'ServerConfigScreen' and not current.get('settings_pending', True):
                if expected is None or expected in current.get('settings_message', ''):
                    events.append(current)
                    return current
            if client.poll() is not None:
                raise RuntimeError('Client exited during settings check')
            time.sleep(.2)
        raise TimeoutError(f'Settings result not ready: {current}')

    def action(name, **fields):
        nonlocal after_ms
        ack = client_command(client, output, 'settings-' + name, **fields)
        after_ms = ack['epoch_ms']
        return ack

    def edit(values):
        for key, value in values.items():
            action('set', key='server.' + key, value=str(value))
        action('save')
        result = wait('服务器配置已同步')
        stored = tomllib.loads(server_config.read_text(encoding='utf-8'))['server']
        for key, value in values.items():
            node = stored
            for part in key.split('.'):
                node = node[part]
            if (','.join(node) if isinstance(node, list) else str(node).lower()) != str(value).lower():
                raise AssertionError((key, node, value))
        return result

    if not receive:
        command(server, 'deop LodBench')
        time.sleep(1)
    action('open')
    if not receive:
        wait('权限不足')
        command(server, 'op LodBench')
        action('refresh')
        original = wait('服务器配置已同步')['settings_values']
        assert len(original) == 29
        client_command(client, output, 'screenshot')
        action('stale-reply')
        action('set', key='server.maxBatchColumns', value='129')
        action('save')
        wait('最大每批列数')
        action('refresh')
        wait('服务器配置已同步')
        command(server, 'deop LodBench')
        action('set', key='server.totalBandwidthMbps', value='29')
        action('save')
        wait('权限不足')
        command(server, 'op LodBench')
        action('refresh')
        wait('服务器配置已同步')
        edit({'totalBandwidthMbps': '29.0'})
        edit({'totalBandwidthMbps': '30.0'})
        action('set', key='server.totalBandwidthMbps', value='28')
        action('close')
        action('open')
        assert wait()['settings_values']['server.totalBandwidthMbps'] == '30.0'
    else:
        wait('服务器配置已同步')
        edit({'totalBandwidthMbps': '24.0', 'maxBatchColumns': '8', 'generation.custom.conversionThreads': '3',
              'totalSendMemoryMiB': '48', 'playerSendMemoryMiB': '8', 'generation.snapshotMemoryMiB': '96', 'cacheMemoryMiB': '96'})
        client_command(client, output, 'screenshot')
        edit({'enabled': 'false'})
        time.sleep(3)
        edit({'enabled': 'true'})
        edit({'totalBandwidthMbps': '30.0', 'maxBatchColumns': '16', 'generation.custom.conversionThreads': '4',
              'totalSendMemoryMiB': '64', 'playerSendMemoryMiB': '32', 'generation.snapshotMemoryMiB': '128', 'cacheMemoryMiB': '128'})
        result = edit({'radius': '224', 'distanceBands': '24:0,64:1,96:2'})
        assert result['hello_radius'] == 224 and result['hello_bands'] == ['24:0', '64:1', '96:2']
        time.sleep(3)
        result = edit({'radius': '256', 'distanceBands': '32:0,64:1,96:2'})
        assert result['hello_radius'] == 256 and result['hello_bands'] == ['32:0', '64:1', '96:2']
    client_command(client, output, 'screenshot')
    action('close')
    if receive:
        command(server, 'deop LodBench')
    (output / ('settings-loading.json' if receive else 'settings-before.json')).write_text(json.dumps(events, ensure_ascii=False, indent=2), encoding='utf-8')
