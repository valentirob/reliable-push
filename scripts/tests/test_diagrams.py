"""Tests for scripts/diagrams.py (the documentation diagram generator).

    python3 -m unittest discover -s scripts/tests -p 'test_diagrams.py'

Standard library only; CI runs it in .github/workflows/docs-diagrams.yml.
"""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("diagrams", ROOT / "scripts" / "diagrams.py")
diagrams = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagrams)


def flow(**extra):
    base = {
        "title": "T",
        "desc": "D",
        "width": 480,
        "height": 200,
        "node": [
            {"id": "a", "x": 40, "y": 40, "w": 120, "h": 48, "name": "A"},
            {"id": "b", "x": 320, "y": 40, "w": 120, "h": 48, "name": "B"},
        ],
        "edge": [{"points": [[160, 64], [320, 64]], "label": "GO"}],
    }
    base.update(extra)
    return base


def errors(spec_dict):
    d = diagrams.Diagram(spec_dict, "t")
    svg = d.render()
    return d.errors, svg


class FlowRules(unittest.TestCase):
    def test_clean_diagram_has_no_errors_and_is_accessible(self):
        errs, svg = errors(flow())
        self.assertEqual(errs, [])
        self.assertIn('role="img" aria-labelledby="t-title t-desc"', svg)
        self.assertLess(svg.index("<title"), svg.index("<defs>"))

    def test_off_grid_node_is_reported(self):
        spec_dict = flow()
        spec_dict["node"][0]["x"] = 42
        self.assertTrue(any("off the 4px grid" in e for e in errors(spec_dict)[0]))

    def test_diagonal_segment_is_reported(self):
        errs, _ = errors(flow(edge=[{"points": [[160, 64], [320, 80]]}]))
        self.assertTrue(any("diagonal" in e for e in errs))

    def test_edge_behind_another_node_is_reported(self):
        spec_dict = flow()
        spec_dict["node"].append({"id": "c", "x": 200, "y": 40, "w": 80, "h": 48, "name": "C"})
        self.assertTrue(any("passes behind node c" in e for e in errors(spec_dict)[0]))

    def test_label_over_a_node_is_reported(self):
        errs, _ = errors(flow(edge=[{"points": [[160, 64], [200, 64]], "label": "A VERY LONG LABEL HERE"}]))
        self.assertTrue(any("overlaps node" in e for e in errs))

    def test_text_wider_than_its_box_is_reported(self):
        spec_dict = flow()
        spec_dict["node"][0]["name"] = "A name far too long for this box"
        self.assertTrue(any("wider than the box" in e for e in errors(spec_dict)[0]))

    def test_unknown_node_kind_fails(self):
        spec_dict = flow()
        spec_dict["node"][0]["kind"] = "glowing"
        with self.assertRaises(diagrams.LayoutError):
            diagrams.Diagram(spec_dict, "t").render()


class Animation(unittest.TestCase):
    def test_steps_get_tokens_keyframes_and_a_reduced_motion_fallback(self):
        spec_dict = flow(animate=True, edge=[{"points": [[160, 64], [320, 64]], "label": "GO", "step": 1}])
        errs, svg = errors(spec_dict)
        self.assertEqual(errs, [])
        self.assertIn('class="tk tk1"', svg)
        self.assertIn("@keyframes t-tk", svg)
        self.assertIn("prefers-reduced-motion: reduce", svg)
        self.assertNotIn("<script", svg)

    def test_static_diagram_has_no_animation(self):
        self.assertNotIn("@keyframes", errors(flow())[1])


class Sequence(unittest.TestCase):
    def test_sequence_grows_with_its_rows(self):
        spec_dict = {
            "type": "sequence", "title": "S", "desc": "D", "width": 480, "height": 0,
            "actor": [{"id": "a", "name": "A"}, {"id": "b", "name": "B"}],
            "row": [{"kind": "call", "from": "a", "to": "b", "text": "hi"},
                    {"kind": "return", "from": "b", "to": "a", "text": "ok"}],
        }
        errs, svg = errors(spec_dict)
        self.assertEqual(errs, [])
        self.assertIn(">hi</text>", svg)
        self.assertNotIn('viewBox="0 0 480 0"', svg)


class Palette(unittest.TestCase):
    def test_every_text_colour_clears_4_5_to_1(self):
        self.assertEqual(diagrams.check_contrast(), [])


if __name__ == "__main__":
    unittest.main()
