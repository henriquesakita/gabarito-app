"""Gera gabarito.pdf (A4) com a MESMA geometria usada em OmrScanner.kt.  pip install reportlab"""
from reportlab.pdfgen import canvas
W, H = 800, 1100
COLS, ROWS, OPTS = 2, 25, 5
X0, COL_GAP, OPT_GAP, Y0, ROW_GAP = 120, 360, 50, 140, 36
MARK, MC, R = 40, 40, 12
s, PH = 595 / W, 842
X = lambda x: x * s
Y = lambda y: PH - y * s

c = canvas.Canvas("gabarito.pdf", pagesize=(595, PH))
for cx, cy in [(MC, MC), (W - MC, MC), (W - MC, H - MC), (MC, H - MC)]:
    c.rect(X(cx - MARK / 2), Y(cy + MARK / 2), MARK * s, MARK * s, fill=1, stroke=0)
c.setFont("Helvetica", 10)
c.drawString(X(100), Y(85), "Nome: ____________________________   Turma: ______   Data: ___/___/____")
for col in range(COLS):
    for o in range(OPTS):
        c.drawCentredString(X(X0 + col * COL_GAP + o * OPT_GAP), Y(112), "ABCDE"[o])
for q in range(COLS * ROWS):
    y = Y0 + (q % ROWS) * ROW_GAP
    xb = X0 + (q // ROWS) * COL_GAP
    c.drawRightString(X(xb - 28), Y(y - 4), str(q + 1))
    for o in range(OPTS):
        c.circle(X(xb + o * OPT_GAP), Y(y), R * s, stroke=1, fill=0)
c.save()
print("gabarito.pdf gerado")
