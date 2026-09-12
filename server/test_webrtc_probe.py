import unittest
from webrtc_probe import candidate_bucket


class CandidateSummaryTest(unittest.TestCase):
    def test_fixed_categories_without_addresses(self):
        self.assertEqual("udp_host_v4_private", candidate_bucket(
            "candidate:1 1 UDP 123 192.168.8.2 45000 typ host"))
        self.assertEqual("tcp_host_v6_loopback", candidate_bucket(
            "candidate:1 1 TCP 123 ::1 45000 typ host tcptype passive"))

    def test_mdns_and_reflexive_candidates(self):
        self.assertEqual("udp_host_name_unknown", candidate_bucket(
            "candidate:1 1 UDP 123 example.local 45000 typ host"))
        self.assertEqual("udp_srflx_v4_public", candidate_bucket(
            "candidate:1 1 UDP 123 8.8.8.8 45000 typ srflx"))

    def test_malformed_and_unknown_inputs_cannot_grow_metric_keys(self):
        self.assertEqual("malformed", candidate_bucket(None))
        self.assertEqual("malformed", candidate_bucket("invalid"))
        self.assertEqual("other_other_name_unknown", candidate_bucket(
            "candidate:1 1 attacker 123 invalid 45000 typ attacker"))


if __name__ == "__main__":
    unittest.main()
