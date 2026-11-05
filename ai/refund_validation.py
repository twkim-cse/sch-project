"""Conservative negative filter: recognize complete loan-only statements.

Unknown wording is retained. This is not a general semantic classifier.
Both the quote and the complete source clause must match the narrow grammar.
"""
import re

_REFUND_LIMIT = r'(?:해약|해지)환급금(?:의\d+(?:\.\d+)?%)?(?:범위|한도)내에서'
_LOAN = r'(?:보험계약대출|대출(?:\(이하["“”\'‘’]?보험계약대출["“”\'‘’]?이라합니다\))?)'
_PERMISSION = re.compile(
    r'(?:보험)?계약자(?:는|가)(?:이계약의)?(?:' + _REFUND_LIMIT + r')?'
    r'(?:회사가정한방법에따라)?' + _LOAN + r'(?:을|를)받을수있습니다'
)
_LIMIT = re.compile(
    r'(?:보험계약대출|대출)(?:의)?한도는(?:해약|해지)환급금'
    r'(?:의\d+(?:\.\d+)?%)?(?:범위내|이내)(?:입니다|로합니다)'
)
_AVAILABILITY_EXCEPTION = re.compile(
    r'다만,?(?:순수보장성보험등)?보험상품의종류에따라보험계약대출이'
    r'제한될수(?:도)?있습니다'
)


def _loan_only(text):
    compact = re.sub(r'\s+', '', text)
    compact = re.sub(r'^(?:[①-⑳]|\d+[.)])', '', compact)
    statements = [s for s in re.split(r'(?<=[다요])[.!?。]', compact) if s]
    if not statements or not (_PERMISSION.fullmatch(statements[0]) or _LIMIT.fullmatch(statements[0])):
        return False
    return all(_AVAILABILITY_EXCEPTION.fullmatch(s) or _PERMISSION.fullmatch(s)
               or _LIMIT.fullmatch(s) for s in statements[1:])


def is_clear_loan_only(quote, clause_text):
    """Reject only fully recognized loan-only quote AND source, never by a word."""
    return _loan_only(quote) and _loan_only(clause_text)
