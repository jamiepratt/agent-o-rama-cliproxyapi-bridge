import copy
import unittest

from run_live import checked_evidence


def safe_record():
    stream = {'complete': True, 'chunks': 1, 'content_chars': 2,
              'usage': {'input': 3, 'output': 2, 'total': 5}, 'trace_model_calls': 1,
              'trace_usage_matches': True, 'stream_resets': 0, 'tool_requests': 0,
              'proxy_requests': 1}
    error = {'error_observed': True, 'trace_failure': True, 'expected_failure_type': True,
             'proxy_requests': 3}
    return {'agent_o_rama': '0.10.0', 'rama': '1.9.0', 'langchain4j': '1.18.1-beta28',
            **{name: copy.deepcopy(stream) for name in ('stream', 'tool', 'retry', 'recovery')},
            **{name: copy.deepcopy(error) for name in ('error', 'timeout')},
            'cancellation': {'subscription_closed_before_completion': True,
                             'agent_completed': True, 'callbacks_after_close': 0,
                             'usage': {'input': 3, 'output': 2, 'total': 5},
                             'upstream_termination_proven': False,
                             'billing_cessation_proven': False, 'proxy_requests': 1}}


class EvidenceBoundaryTests(unittest.TestCase):
    def test_only_fixed_fields_and_numeric_usage_reach_evidence(self):
        self.assertEqual(checked_evidence(safe_record()), safe_record())
        for location in ((), ('stream',), ('stream', 'usage')):
            value = safe_record()
            target = value
            for key in location:
                target = target[key]
            target['response_text'] = 'SECRET_SENTINEL'
            with self.assertRaisesRegex(ValueError, 'Unexpected'):
                checked_evidence(value)

    def test_string_and_boolean_cannot_hide_in_numeric_counts(self):
        for invalid in ('SECRET_SENTINEL', True, -1):
            value = safe_record()
            value['stream']['chunks'] = invalid
            with self.assertRaises(ValueError):
                checked_evidence(value)


if __name__ == '__main__':
    unittest.main()
