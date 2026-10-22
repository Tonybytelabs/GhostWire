/*
 * Copyright 2026 Varakumar.  Part of GhostWire (GPLv3).
 *
 * ghostwire_diag — minimal Qualcomm DIAG client over /dev/diag for NV item
 * read/write, for authorized research on your own device.
 *
 *   ghostwire_diag nvread  <item>
 *   ghostwire_diag nvwrite <item> <up-to-128-byte hex>   (brick risk: own device only)
 *
 * Framing taken from the in-tree diag driver (Levion_kernel_OP9, drivers/char/diag):
 *   write  = [int32 USER_SPACE_DATA_TYPE=0x20][HDLC(cmd + CRC16)]
 *   read   = [int32 type][int32 num_blocks]{ [int32 len][len bytes HDLC payload] }*
 *   session via ioctl(DIAG_IOCTL_SWITCH_LOGGING=7, struct diag_logging_mode_param_t{MEMORY_DEVICE_MODE})
 * NV_READ_F=0x26 / NV_WRITE_F=0x27, packet = [cmd][item:2 LE][data:128][status:2].
 *
 * NOT yet validated on-device — validate nvread round-trips a known item before
 * trusting nvwrite. The caller (GhostWire DIAG tab) read-back-verifies writes.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <poll.h>
#include <sys/ioctl.h>

#define USER_SPACE_DATA_TYPE      0x00000020
#define MEMORY_DEVICE_MODE        2
#define DIAG_IOCTL_SWITCH_LOGGING 7
#define DIAG_CON_APSS             0x0001
#define DIAG_CON_MPSS             0x0002
#define NV_READ_F  0x26
#define NV_WRITE_F 0x27
#define NV_PKT_LEN 133   /* cmd(1) + item(2) + data(128) + status(2) */

struct diag_logging_mode_param_t {
	uint32_t req_mode;
	uint32_t peripheral_mask;
	uint32_t pd_mask;
	uint8_t  mode_param;
	uint8_t  diag_id;
	uint8_t  pd_val;
	uint8_t  reserved;
	int      peripheral;
	int      device_mask;
} __attribute__((packed));

/* diag CRC: CRC-CCITT reflected (0x8408), init 0xFFFF, final complement. */
static uint16_t crc16(const uint8_t *d, size_t n)
{
	uint16_t c = 0xFFFF;
	for (size_t i = 0; i < n; i++) {
		c ^= d[i];
		for (int b = 0; b < 8; b++)
			c = (c & 1) ? (c >> 1) ^ 0x8408 : c >> 1;
	}
	return ~c;
}

/* payload + CRC(LE), byte-stuff 0x7e/0x7d, append 0x7e terminator. */
static size_t hdlc_encode(const uint8_t *in, size_t n, uint8_t *out)
{
	uint8_t tmp[NV_PKT_LEN + 2];
	uint16_t crc = crc16(in, n);
	memcpy(tmp, in, n);
	tmp[n] = crc & 0xff;
	tmp[n + 1] = crc >> 8;
	size_t m = n + 2, o = 0;
	for (size_t i = 0; i < m; i++) {
		if (tmp[i] == 0x7e) { out[o++] = 0x7d; out[o++] = 0x5e; }
		else if (tmp[i] == 0x7d) { out[o++] = 0x7d; out[o++] = 0x5d; }
		else out[o++] = tmp[i];
	}
	out[o++] = 0x7e;
	return o;
}

/* decode one HDLC frame; returns payload length without the 2 CRC bytes. */
static size_t hdlc_decode(const uint8_t *in, size_t n, uint8_t *out, size_t omax)
{
	size_t o = 0;
	for (size_t i = 0; i < n && o < omax; i++) {
		uint8_t c = in[i];
		if (c == 0x7e) break;
		if (c == 0x7d && i + 1 < n) c = in[++i] ^ 0x20;
		out[o++] = c;
	}
	return o >= 2 ? o - 2 : 0;
}

static uint32_t rd32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }

/* Scan a memory-device read buffer for a frame whose first byte == want. */
static int find_resp(const uint8_t *buf, ssize_t len, uint8_t want,
		     uint8_t *out, size_t omax, size_t *outlen)
{
	if (len < 8) return 0;
	size_t off = 4;                 /* skip data_type */
	uint32_t num = rd32(buf + off); off += 4;
	for (uint32_t i = 0; i < num && off + 4 <= (size_t)len; i++) {
		uint32_t blen = rd32(buf + off); off += 4;
		if (off + blen > (size_t)len) break;
		size_t dl = hdlc_decode(buf + off, blen, out, omax);
		if (dl > 0 && out[0] == want) { *outlen = dl; return 1; }
		off += blen;
	}
	return 0;
}

int main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr, "usage: %s nvread <item> | nvwrite <item> <hex up to 128 bytes>\n", argv[0]);
		return 2;
	}
	int wr = !strcmp(argv[1], "nvwrite");
	if (!wr && strcmp(argv[1], "nvread")) { fprintf(stderr, "unknown op %s\n", argv[1]); return 2; }
	int item = atoi(argv[2]);

	uint8_t cmd[NV_PKT_LEN];
	memset(cmd, 0, sizeof cmd);
	cmd[0] = wr ? NV_WRITE_F : NV_READ_F;
	cmd[1] = item & 0xff;
	cmd[2] = (item >> 8) & 0xff;
	if (wr) {
		if (argc < 4) { fprintf(stderr, "nvwrite needs hex data\n"); return 2; }
		const char *h = argv[3];
		size_t hn = strlen(h);
		if (hn % 2 || hn / 2 > 128) { fprintf(stderr, "hex must be even, <=256 chars\n"); return 2; }
		for (size_t i = 0; i < hn / 2; i++) {
			unsigned b;
			if (sscanf(h + 2 * i, "%2x", &b) != 1) { fprintf(stderr, "bad hex\n"); return 2; }
			cmd[3 + i] = (uint8_t)b;
		}
	}

	int fd = open("/dev/diag", O_RDWR);
	if (fd < 0) { fprintf(stderr, "open /dev/diag: %s\n", strerror(errno)); return 1; }

	struct diag_logging_mode_param_t p;
	memset(&p, 0, sizeof p);
	p.req_mode = MEMORY_DEVICE_MODE;
	p.peripheral_mask = DIAG_CON_APSS | DIAG_CON_MPSS;
	p.device_mask = 1;                 /* DIAG_MD_LOCAL bit */
	if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &p) < 0)
		fprintf(stderr, "warn: SWITCH_LOGGING: %s (continuing)\n", strerror(errno));

	uint8_t pkt[512];
	uint32_t type = USER_SPACE_DATA_TYPE;
	memcpy(pkt, &type, 4);
	size_t fl = hdlc_encode(cmd, NV_PKT_LEN, pkt + 4);
	if (write(fd, pkt, 4 + fl) < 0) { fprintf(stderr, "write: %s\n", strerror(errno)); close(fd); return 1; }

	/* read responses (modem reply is interleaved with logs); wait up to ~3s. */
	uint8_t buf[65536], resp[NV_PKT_LEN + 8];
	size_t rl = 0;
	for (int tries = 0; tries < 30; tries++) {
		struct pollfd pfd = { fd, POLLIN, 0 };
		if (poll(&pfd, 1, 100) <= 0) continue;
		ssize_t n = read(fd, buf, sizeof buf);
		if (n <= 0) continue;
		if (find_resp(buf, n, cmd[0], resp, sizeof resp, &rl)) {
			uint16_t status = resp[131] | (resp[132] << 8);
			if (wr) {
				printf("nvwrite item=%d status=%u\n", item, status);
			} else {
				printf("nvread item=%d status=%u data=", item, status);
				for (int i = 0; i < 128; i++) printf("%02x", resp[3 + i]);
				printf("\n");
			}
			close(fd);
			return status == 0 ? 0 : 1;
		}
	}
	fprintf(stderr, "no NV response (0x%02x) within timeout\n", cmd[0]);
	close(fd);
	return 1;
}
