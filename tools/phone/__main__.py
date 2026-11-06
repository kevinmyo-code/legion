"""`python tools/phone <command>` - see cli.py, and `.claude/skills/verify-on-phone/SKILL.md`."""
import sys
from pathlib import Path

# Run as a directory (`python tools/phone`), so make `phone` importable as a package from tools/.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from phone.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
